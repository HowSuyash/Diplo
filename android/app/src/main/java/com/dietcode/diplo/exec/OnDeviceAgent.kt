package com.dietcode.diplo.exec

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.dietcode.diplo.exec.ScreenElement.Kind
import com.dietcode.diplo.intent.LocalBrain
import kotlinx.coroutines.delay

/**
 * Does a task inside any app on the phone itself: Google ARTEMIS's observe, decide, act loop, but
 * nothing leaves the phone. Diplo's accessibility service is the eyes and hands. Decisions come
 * from rules first, which are instant: open the app on the right screen (DeepLinks), tap the
 * thing the user named, search for it. The on-device model (LocalBrain) settles only the rest.
 */
class OnDeviceAgent(private val context: Context, private val executor: PhoneExecutor) {

    data class Outcome(val speak: String, val ok: Boolean)

    /** [params]: app, goal, and when known target, destination, query, message. Cancellable. */
    suspend fun run(params: Map<String, String>, onStep: (String) -> Unit): Outcome {
        // The task works the screen, so it must not time out and lock halfway through.
        @Suppress("DEPRECATION") // the screen-on lock for a service; there is no window to flag
        val awake = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "diplo:task")
        awake?.acquire(TASK_MS)
        try {
            return work(params, onStep)
        } finally {
            if (awake?.isHeld == true) awake.release()
        }
    }

    private suspend fun work(params: Map<String, String>, onStep: (String) -> Unit): Outcome {
        val service = DiploAccessibilityService.instance
            ?: return Outcome("पहले सेटिंग्स में Diplo की एक्सेसिबिलिटी चालू कीजिए।", ok = false)
        val app = params["app"].orEmpty()
        val goal = params["goal"].orEmpty().ifBlank { "Open $app" }
        val target = params["target"].orEmpty().trim()
        val query = params["query"].orEmpty().trim()
        if (locked()) return LOCKED
        val opened = open(app, params, onStep) ?: return Outcome("$app नाम का ऐप नहीं मिला।", ok = false)
        delay(OPEN_MS)
        // A search the link already ran is done: the app is on its results.
        if (query.isNotEmpty() && target.isEmpty() && opened == Opened.ON_SCREEN) return Outcome(DONE, ok = true)
        // "settings mein bluetooth kholo": the link opened that very page.
        if (opensPage(goal) && DeepLinks.settingsAction(params).let { it != null && it != Settings.ACTION_SETTINGS }) {
            return Outcome(DONE, ok = true)
        }

        val destination = params["destination"].orEmpty().trim()
        val mode = params["mode"] ?: listOf("bike", "auto", "cab").firstOrNull { it in goal.lowercase() }
        // A ride: the Uber link already set the drop, so its recipe starts at picking the ride.
        var ride = if (opened == Opened.ON_SCREEN) RideRecipe.Stage.MODE else RideRecipe.Stage.TYPE
        var booked = 0

        val done = mutableListOf<String>()
        var searched = false
        var scrolls = 0
        val inSettings = "setting" in app.lowercase()
        val menus = mutableSetOf<String>()
        var before: List<ScreenElement>? = null
        var unchanged = 0
        for (step in 1..MAX_STEPS) {
            // A locked phone shows only its lock screen: nothing in the app can be reached.
            if (locked()) return LOCKED
            val elements = service.elements()
            // Two moves in a row that changed nothing: the loop is going nowhere, so stop.
            val seen = elements.map { it.seen }
            unchanged = if (seen == before) unchanged + 1 else 0
            if (unchanged >= 2) return STUCK
            before = seen
            if (destination.isNotEmpty() && ride != RideRecipe.Stage.DONE) {
                val move = RideRecipe.next(elements.map { it.seen }, destination, mode, ride, booked)
                val element = move?.let { elements.getOrNull(it.n - 1) }
                if (move != null && element != null) {
                    if (move.text != null) service.type(element, move.text, submit = false) else service.tap(element)
                    if (move.books) booked++
                    ride = move.next
                    done += move.say
                    onStep(move.say)
                    delay(SETTLE_MS)
                    if (ride == RideRecipe.Stage.DONE) return BOOKED
                    continue
                }
            }
            // A search with no link: type it into the app's search box, and the results are the answer.
            if (query.isNotEmpty() && target.isEmpty() && !searched) {
                searched = true
                if (search(service, elements, query)) {
                    onStep("खोजा: $query")
                    delay(SETTLE_MS)
                    return finished(service, goal)
                }
            }
            if (target.isNotEmpty()) {
                // The thing the user named is on screen: tap it. A toggle already as wanted is done.
                found(elements, target)?.let { element ->
                    if (isPayment(element.seen.label)) return PAYMENT
                    // Settings rows often carry the label and leave the switch beside it unlabelled.
                    // Only a goal that turns something on or off flips a switch; "Open Stopwatch" just taps.
                    val flips = flips(goal)
                    val toggle = if (flips) toggleFor(elements, element) else null
                    if (toggle != null && toggle.seen.checked == wantsOn(goal)) {
                        return Outcome("यह पहले से ही हो रखा है।", ok = true)
                    }
                    service.tap(toggle ?: element)
                    onStep("दबाया: ${element.seen.label}")
                    delay(SETTLE_MS)
                    // A row that opened a page of its own ("Dark theme" → "Use Dark theme"): flip it there.
                    if (flips && toggle == null) {
                        val page = service.elements()
                        val switches = page.filter { it.seen.kind == Kind.TOGGLE }
                        val switch = switches.firstOrNull { it.seen.label.contains(target, ignoreCase = true) }
                            ?: switches.singleOrNull()
                        if (switch != null && switch.seen.checked != wantsOn(goal)) {
                            service.tap(switch)
                            onStep("दबाया: ${switch.seen.label.ifEmpty { target }}")
                            delay(SETTLE_MS)
                        }
                    }
                    return Outcome(DONE, ok = true)
                }
                // Not on screen. Settings has a search for its own pages; another app's search box
                // finds content (videos about "history"), so there look behind its profile or menu tab.
                if (inSettings && !searched && search(service, elements, target)) {
                    searched = true
                    onStep("खोजा: $target")
                    delay(SETTLE_MS)
                    continue
                }
                val menu = elements.firstOrNull { isMenu(it.seen) && it.seen.label !in menus }
                if (!inSettings && menu != null && menus.size < MAX_MENUS) {
                    menus += menu.seen.label
                    service.tap(menu)
                    onStep("दबाया: ${menu.seen.label}")
                    delay(SETTLE_MS)
                    continue
                }
                if (scrolls < MAX_SCROLLS && service.scrollDown()) {
                    scrolls++
                    onStep("नीचे देखा")
                    delay(SETTLE_MS)
                    continue
                }
            }
            val choice = LocalBrain.step(context, goal, app, elements.map { it.seen }, done)
            Log.i(TAG, "agent step $step: $choice")
            when (choice) {
                is AgentStep.Tap -> {
                    val element = elements.getOrNull(choice.n - 1) ?: return STUCK
                    if (isPayment(element.seen.label)) return PAYMENT
                    service.tap(element)
                    done += "tapped [${choice.n}] ${element.seen.label}"
                    onStep("दबाया: ${element.seen.label}")
                }
                is AgentStep.Type -> {
                    val element = elements.getOrNull(choice.n - 1) ?: return STUCK
                    service.type(element, choice.text)
                    done += "typed \"${choice.text}\" into [${choice.n}]"
                    onStep("लिखा: ${choice.text}")
                }
                AgentStep.Scroll -> {
                    service.scrollDown()
                    done += "scrolled down"
                    onStep("नीचे देखा")
                }
                AgentStep.Back -> {
                    service.back()
                    done += "went back"
                    onStep("पीछे गया")
                }
                AgentStep.Done -> return finished(service, goal)
                AgentStep.Stuck -> return STUCK
            }
            delay(SETTLE_MS)
        }
        return STUCK
    }

    // A question ("my last post's likes", "the price") is answered from the screen the task ended
    // on, by the on-device model, so what is on the screen stays on the phone. Else just done.
    private suspend fun finished(service: DiploAccessibilityService, goal: String): Outcome {
        if (!asksSomething(goal)) return Outcome(DONE, ok = true)
        delay(SETTLE_MS)
        val reply = LocalBrain.answer(context, goal, service.readScreen(maxItems = 60))
        return Outcome(reply ?: DONE, ok = true)
    }

    private fun locked() = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private enum class Opened { ON_SCREEN, AT_START }

    /** Opens the app: on the task's own screen when a link knows it, else on its start screen. */
    private fun open(app: String, params: Map<String, String>, onStep: (String) -> Unit): Opened? {
        DeepLinks.settingsAction(params)?.let { action ->
            if (executor.start(Intent(action))) {
                onStep("सेटिंग्स खोली")
                return Opened.ON_SCREEN
            }
        }
        DeepLinks.link(app, params)?.let { link ->
            if (executor.start(DeepLinks.intent(link))) {
                onStep("$app खोला")
                return Opened.ON_SCREEN
            }
        }
        val label = executor.launch(app) ?: return null
        onStep("$label खोला")
        return Opened.AT_START
    }

    // The shortest label containing the target: "Dark theme" over a long description of it.
    private fun found(elements: List<DiploAccessibilityService.Element>, target: String) =
        elements.filter { it.seen.kind != Kind.INPUT && it.seen.label.contains(target, ignoreCase = true) }
            .minByOrNull { it.seen.label.length }

    // The element itself if it is a switch, else a switch just after it in the same row.
    private fun toggleFor(elements: List<DiploAccessibilityService.Element>, element: DiploAccessibilityService.Element) =
        if (element.seen.kind == Kind.TOGGLE) element
        else elements.drop(element.seen.n).take(3).firstOrNull { it.seen.kind == Kind.TOGGLE }

    private suspend fun search(
        service: DiploAccessibilityService,
        elements: List<DiploAccessibilityService.Element>,
        target: String,
    ): Boolean {
        val input = elements.firstOrNull { it.seen.kind == Kind.INPUT }
        if (input != null) return service.type(input, target)
        // Not "Search with your voice": YouTube's mic opened a listening screen.
        val button = elements.firstOrNull {
            it.seen.label.contains("search", ignoreCase = true) && !notTyped.containsMatchIn(it.seen.label)
        } ?: return false
        service.tap(button)
        delay(SETTLE_MS) // the search box opens
        val opened = service.elements().firstOrNull { it.seen.kind == Kind.INPUT } ?: return false
        return service.type(opened, target)
    }

    companion object {
        private const val TAG = "Diplo"
        private const val MAX_STEPS = 8
        private const val MAX_SCROLLS = 2
        private const val MAX_MENUS = 3
        private const val OPEN_MS = 1800L
        private const val SETTLE_MS = 1200L
        private const val TASK_MS = 120_000L
        private const val DONE = "हो गया।"
        private val STUCK = Outcome("यहां मैं आगे नहीं बढ़ पाया। बाकी आप कर लीजिए।", ok = false)
        private val LOCKED = Outcome("फ़ोन लॉक है। अनलॉक करके फिर से बोलिए।", ok = false)
        private val BOOKED = Outcome("बुकिंग का बटन दबा दिया। आगे की जानकारी ऐप में देखिए।", ok = true)
        // Paying is always the user's own tap: UPI Lite pays small amounts without a PIN.
        private val PAYMENT = Outcome("पेमेंट की स्क्रीन आ गई है। आखिरी बटन आप खुद दबाइए।", ok = true)
        private val payment = Regex("""\b(pay|payment|send money|proceed to pay)\b|भुगतान|पेमेंट""", RegexOption.IGNORE_CASE)

        fun isPayment(label: String) = payment.containsMatchIn(label)

        private val notTyped = Regex("""\b(voice|mic|camera|image|lens|photo)\b""", RegexOption.IGNORE_CASE)
        // The tabs apps keep their pages behind: YouTube's "You", Instagram's "Profile", WhatsApp's "More options".
        private val menu = Regex(
            """^(you|library|profile|account|menu|more|more options|settings|open navigation drawer|navigation drawer""" +
                """|आप|प्रोफ़ाइल|प्रोफाइल|मेनू|ज़्यादा विकल्प|सेटिंग)(?![\p{L}\p{M}])""", // not \b: Devanagari isn't a \w
            RegexOption.IGNORE_CASE,
        )

        /** Whether [element] is a tab or menu button that leads to an app's own pages. */
        fun isMenu(element: ScreenElement) =
            element.kind == Kind.BUTTON && element.label.length <= 30 && menu.containsMatchIn(element.label.trim())

        /** Whether the goal wants something read back, not just done. */
        fun asksSomething(goal: String) =
            Regex("""\b(how many|how much|what|which|tell|check|find out|price|count)\b|\?""", RegexOption.IGNORE_CASE)
                .containsMatchIn(goal)

        /** Whether the goal turns something on ("Turn on Dark theme") or off. */
        fun wantsOn(goal: String) = !Regex("""\b(off|disable|turn off)\b|बंद""", RegexOption.IGNORE_CASE).containsMatchIn(goal)

        /** Whether the goal flips a switch at all, rather than opening or pressing something. */
        fun flips(goal: String) =
            Regex("""\b(turn|switch|enable|disable|on|off)\b|चालू|बंद""", RegexOption.IGNORE_CASE).containsMatchIn(goal)

        /** Whether the goal is only to reach a page ("Open Stopwatch"). */
        fun opensPage(goal: String) = goal.trim().startsWith("Open ", ignoreCase = true) && !asksSomething(goal)
    }
}
