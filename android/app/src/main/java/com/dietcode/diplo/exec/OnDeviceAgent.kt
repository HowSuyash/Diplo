package com.dietcode.diplo.exec

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.dietcode.diplo.exec.ScreenElement.Kind
import com.dietcode.diplo.intent.LocalBrain
import kotlinx.coroutines.delay

/**
 * Does anything a user does on the phone, the way Google ARTEMIS does it, but on the phone
 * itself: the on-device model plans the request, then looks at the screen and picks one action
 * at a time (tap, type, scroll, back, open an app) until the goal is reached. Diplo's
 * accessibility service is the eyes and hands. No app has a script; nothing leaves the phone.
 *
 * Safety holds for every app: a task that books, sends, calls or pays asks "haan" before it
 * starts, a Pay button is never tapped, password fields are never read, and the task stops when
 * the phone locks, the app closes, or the user says something new.
 */
class OnDeviceAgent(private val context: Context, private val executor: PhoneExecutor) {

    data class Outcome(val speak: String, val ok: Boolean)

    /** How the agent talks to the user while it works. */
    interface Io {
        /** Progress shown on screen ("Uber खोला"). */
        fun step(text: String)

        /** Spoken now ("ठीक है, कर रहा हूं।"). */
        fun say(text: String)

        /** Asks [question] and waits for a yes or no. */
        suspend fun confirm(question: String): Boolean

        /** Asks [question] and waits for the spoken answer; null if none came. */
        suspend fun ask(question: String): String?
    }

    /** Plans and does [request], the user's own words. Cancellable. */
    suspend fun run(request: String, io: Io): Outcome {
        // The task works the screen, so it must not time out and lock halfway through.
        @Suppress("DEPRECATION") // the screen-on lock for a service; there is no window to flag
        val awake = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "diplo:task")
        awake?.acquire(TASK_MS)
        try {
            return work(request, io)
        } finally {
            if (awake?.isHeld == true) awake.release()
        }
    }

    private suspend fun work(request: String, io: Io): Outcome {
        io.step("सोच रहा हूं")
        // The planner answers, asks for what is missing ("कहां जाना है?"), or plans a task.
        var asked = request
        var task: AgentPlan.Task? = null
        for (round in 0..MAX_ASKS) {
            when (val plan = LocalBrain.plan(context, asked) ?: return NOT_UNDERSTOOD) {
                is AgentPlan.Reply -> return Outcome(plan.say, ok = true)
                is AgentPlan.Ask -> {
                    if (round == MAX_ASKS) return NOT_UNDERSTOOD
                    val answer = io.ask(plan.say) ?: return Outcome("ठीक है, रहने देते हैं।", ok = false)
                    asked = "$asked. ${plan.say} $answer"
                }
                is AgentPlan.Task -> {
                    task = plan
                    break
                }
            }
        }
        task ?: return NOT_UNDERSTOOD

        // Acting for the user asks "haan" first, naming exactly the goal that will run.
        if (LocalBrain.actsForUser(task.goal) && !io.confirm("फ़ोन पर यह करूं: ${task.goal}?")) {
            return Outcome("ठीक है, नहीं किया।", ok = true)
        }
        val service = DiploAccessibilityService.instance
            ?: return Outcome("पहले सेटिंग्स में Diplo की एक्सेसिबिलिटी चालू कीजिए।", ok = false)
        if (locked()) return LOCKED
        io.say("ठीक है, कर रहा हूं।")
        app = executor.launch(task.app) ?: return Outcome("${task.app} नाम का ऐप नहीं मिला।", ok = false)
        io.step("$app खोला")
        settle(service, OPEN_MS)
        return follow(service, task, io)
    }

    private var app = "" // the app in front, as the task opened it

    // The plan's steps, done by skills that work in any app (Skills): fast, no model. A step
    // outside the grammar, or naming something the screen doesn't show even after a scroll, goes
    // to the model, for that one step.
    private suspend fun follow(service: DiploAccessibilityService, task: AgentPlan.Task, io: Io): Outcome {
        if (task.steps.isEmpty()) return operate(service, task, io, MAX_STEPS)
        for (raw in task.steps) {
            if (locked()) return LOCKED
            if (left(service)) return LEFT
            val step = PlanStep.parse(raw)
            // "open Uber" first: the task has opened it already.
            if (step?.verb == PlanStep.Verb.OPEN && Skills.score(app, step.target) >= 70) continue
            var did = step?.let { perform(service, it, io) } ?: Did.MISSED
            if (did == Did.MISSED && step?.verb in LOOKED_FOR && service.scrollDown()) {
                delay(SETTLE_MS)
                did = perform(service, step!!, io)
            }
            when (did) {
                Did.PAYMENT -> return PAYMENT
                Did.MISSED -> {
                    Log.i(TAG, "no skill for \"$raw\" on this screen: the model takes it")
                    val outcome = operate(service, task.copy(goal = raw, steps = emptyList()), io, STEP_TRIES)
                    if (!outcome.ok) return outcome
                }
                Did.OK -> delay(SETTLE_MS)
            }
        }
        return Outcome(DONE, ok = true)
    }

    private enum class Did { OK, MISSED, PAYMENT }

    // One step by its skill. MISSED when the screen doesn't show what it names.
    private suspend fun perform(service: DiploAccessibilityService, step: PlanStep, io: Io): Did {
        val elements = service.elements()
        val seen = elements.map { it.seen }
        fun on(found: ScreenElement?) = found?.let { f -> elements.firstOrNull { it.seen.n == f.n } }
        when (step.verb) {
            PlanStep.Verb.OPEN -> {
                app = executor.launch(step.target) ?: return Did.MISSED
                io.step("$app खोला")
                settle(service, OPEN_MS)
            }
            PlanStep.Verb.TAP -> {
                val element = on(Skills.best(seen, step.target)) ?: return Did.MISSED
                // Paying is always the user's own tap: UPI Lite pays small amounts without a PIN.
                if (isPayment(element.seen.label)) return Did.PAYMENT
                service.tap(element)
                io.step("दबाया: ${element.seen.label.take(40)}")
            }
            PlanStep.Verb.TYPE, PlanStep.Verb.SEARCH -> {
                // No box yet: what opens one ("Where to?", "Search") first.
                val input = on(Skills.input(seen)) ?: run {
                    val opener = on(Skills.searchOpener(seen)) ?: return Did.MISSED
                    service.tap(opener)
                    delay(SETTLE_MS)
                    val again = service.elements()
                    Skills.input(again.map { it.seen })?.let { box -> again.firstOrNull { it.seen.n == box.n } }
                } ?: return Did.MISSED
                service.type(input, step.target, submit = step.verb == PlanStep.Verb.SEARCH)
                io.step("लिखा: ${step.target}")
            }
            PlanStep.Verb.TURN_ON, PlanStep.Verb.TURN_OFF -> {
                val wanted = step.verb == PlanStep.Verb.TURN_ON
                val row = on(Skills.best(seen, step.target)) ?: return Did.MISSED
                // Settings rows often carry the label and leave the switch beside it unlabelled.
                val toggle = if (row.seen.kind == Kind.TOGGLE) row
                else elements.drop(row.seen.n).take(3).firstOrNull { it.seen.kind == Kind.TOGGLE }
                if (toggle != null) {
                    if (toggle.seen.checked != wanted) service.tap(toggle)
                } else {
                    // A row that opens a page of its own ("Dark theme" → "Use Dark theme"): its switch.
                    service.tap(row)
                    delay(SETTLE_MS)
                    val switches = service.elements().filter { it.seen.kind == Kind.TOGGLE }
                    val switch = Skills.best(switches.map { it.seen }, step.target)?.let { s -> switches.firstOrNull { it.seen.n == s.n } }
                        ?: switches.singleOrNull()
                    if (switch != null && switch.seen.checked != wanted) service.tap(switch)
                }
                io.step("${if (wanted) "चालू" else "बंद"} किया: ${row.seen.label.take(40)}")
            }
            PlanStep.Verb.SCROLL -> if (!service.scrollDown()) return Did.MISSED
            PlanStep.Verb.BACK -> service.back()
        }
        return Did.OK
    }

    // The operator: look, decide, act, until the model says done.
    private suspend fun operate(service: DiploAccessibilityService, task: AgentPlan.Task, io: Io, maxSteps: Int): Outcome {
        val done = mutableListOf<String>()
        var before: List<ScreenElement>? = null
        var unchanged = 0
        var stuck = 0
        for (step in 1..maxSteps) {
            // A locked phone shows only its lock screen; a closed app leaves Diplo or the home
            // screen in front. Never tap on whatever is there instead.
            if (locked()) return LOCKED
            if (left(service)) return LEFT
            val elements = service.elements()
            val shown = shown(elements)
            val seen = shown.map { it.seen }
            unchanged = if (seen == before) unchanged + 1 else 0
            if (unchanged >= MAX_UNCHANGED) return STUCK
            before = seen
            val note = if (unchanged > 0) "Your last action changed nothing." else null

            val choice = LocalBrain.act(context, task.goal, task.steps, app, seen, done, note)
            Log.i(TAG, "agent step $step: $choice")
            // The model takes seconds and the screen can move meanwhile, so what it chose is found
            // again on the screen as it is now, or not acted on at all.
            val now = shown(service.elements())
            fun chosen(n: Int): DiploAccessibilityService.Element? {
                val was = seen.getOrNull(n - 1) ?: return null
                fun same(e: DiploAccessibilityService.Element) = e.seen.kind == was.kind && e.seen.label == was.label
                return now.getOrNull(n - 1)?.takeIf(::same) ?: now.firstOrNull(::same)
            }
            when (choice) {
                is AgentStep.Tap -> {
                    val element = chosen(choice.n) ?: continue
                    // Paying is always the user's own tap: UPI Lite pays small amounts without a PIN.
                    if (isPayment(element.seen.label)) return PAYMENT
                    service.tap(element)
                    done += "tapped [${choice.n}] ${element.seen.label.take(30)}"
                    io.step("दबाया: ${element.seen.label.take(40)}")
                }
                is AgentStep.Type -> {
                    val element = chosen(choice.n) ?: continue
                    service.type(element, choice.text, submit = false)
                    done += "typed \"${choice.text}\" into [${choice.n}]"
                    io.step("लिखा: ${choice.text}")
                }
                AgentStep.Scroll -> {
                    service.scrollDown()
                    done += "scrolled"
                    io.step("नीचे देखा")
                }
                AgentStep.Back -> {
                    // On the app's first screen Back just closes the app.
                    if (done.isEmpty()) {
                        done += "back is not possible here"
                        continue
                    }
                    service.back()
                    done += "went back"
                    io.step("पीछे गया")
                }
                AgentStep.Wait -> {
                    done += "waited"
                    delay(WAIT_MS)
                }
                is AgentStep.Open -> {
                    val label = executor.launch(choice.app)
                    if (label == null) {
                        done += "no app named ${choice.app}"
                    } else {
                        app = label
                        done += "opened $label"
                        io.step("$label खोला")
                        settle(service, OPEN_MS)
                        continue
                    }
                }
                is AgentStep.Ask -> {
                    // The mic needs Diplo in front; after the answer the app comes back as it was.
                    val answer = io.ask(choice.say) ?: return Outcome("ठीक है, रहने देते हैं।", ok = false)
                    done += "asked the user \"${choice.say}\", they said \"$answer\""
                    executor.launch(app)
                    settle(service, OPEN_MS)
                    continue
                }
                is AgentStep.Done -> return Outcome(choice.say.ifEmpty { DONE }, ok = true)
                AgentStep.Stuck -> {
                    // An unreadable reply: once more, then give up.
                    if (++stuck >= MAX_STUCK) return STUCK
                    continue
                }
            }
            stuck = 0
            delay(SETTLE_MS)
        }
        return STUCK
    }

    // What the model sees: named things and input boxes, numbered from 1, each line short. Frames
    // without a name only cost prompt time on a phone CPU.
    private fun shown(elements: List<DiploAccessibilityService.Element>) =
        elements.filter { it.seen.label.isNotBlank() || it.seen.kind == Kind.INPUT }
            .take(MAX_SHOWN)
            .mapIndexed { i, e -> e.copy(seen = e.seen.copy(n = i + 1, label = e.seen.label.take(LABEL_CHARS))) }

    // After opening an app: at least [ms], longer while a cold start still shows a blank splash.
    private suspend fun settle(service: DiploAccessibilityService, ms: Long) {
        delay(ms)
        repeat(LOAD_TRIES) { if (service.elements().size < MIN_ELEMENTS) delay(LOAD_MS) }
    }

    private fun locked() = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private val launcher: String? by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
    }

    private fun left(service: DiploAccessibilityService): Boolean {
        val front = service.activePackage() ?: return false
        return front == context.packageName || front == launcher
    }

    companion object {
        private const val TAG = "Diplo"
        private const val MAX_ASKS = 2
        private const val MAX_STEPS = 15
        private const val STEP_TRIES = 3 // model moves for one plan step a skill couldn't do
        private val LOOKED_FOR = setOf(PlanStep.Verb.TAP, PlanStep.Verb.TURN_ON, PlanStep.Verb.TURN_OFF)
        private const val MAX_UNCHANGED = 3
        private const val MAX_STUCK = 2
        private const val MAX_SHOWN = 30
        private const val LABEL_CHARS = 45
        private const val OPEN_MS = 1800L
        private const val SETTLE_MS = 1000L
        private const val WAIT_MS = 2000L
        private const val TASK_MS = 300_000L
        private const val LOAD_TRIES = 8
        private const val LOAD_MS = 500L
        private const val MIN_ELEMENTS = 3
        private const val DONE = "हो गया।"
        private val NOT_UNDERSTOOD = Outcome("माफ़ कीजिए, मैं समझ नहीं पाया। फिर से बोलिए।", ok = false)
        private val STUCK = Outcome("यहां मैं आगे नहीं बढ़ पाया। बाकी आप कर लीजिए।", ok = false)
        private val LOCKED = Outcome("फ़ोन लॉक है। अनलॉक करके फिर से बोलिए।", ok = false)
        private val LEFT = Outcome("ऐप बंद हो गया, इसलिए काम रोक दिया।", ok = false)
        private val PAYMENT = Outcome("पेमेंट की स्क्रीन आ गई है। आखिरी बटन आप खुद दबाइए।", ok = true)
        private val payment = Regex("""\b(pay|payment|send money|proceed to pay)\b|भुगतान|पेमेंट""", RegexOption.IGNORE_CASE)

        fun isPayment(label: String) = payment.containsMatchIn(label)
    }
}
