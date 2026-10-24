package com.dietcode.diplo.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dietcode.diplo.BuildConfig
import com.dietcode.diplo.MainActivity
import com.dietcode.diplo.exec.DiploAccessibilityService
import com.dietcode.diplo.exec.OnDeviceAgent
import com.dietcode.diplo.exec.PhoneExecutor
import com.dietcode.diplo.intent.AgentIntent
import com.dietcode.diplo.intent.AppTaskRules
import com.dietcode.diplo.intent.LocalBrain
import com.dietcode.diplo.intent.OfflineParser
import com.dietcode.diplo.intent.SmallTalk
import com.dietcode.diplo.intent.WebRules
import com.dietcode.diplo.net.BackendClient
import com.dietcode.diplo.net.WebEvent
import com.dietcode.diplo.voice.Speaker
import com.dietcode.diplo.voice.SpeechInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource
import java.io.IOException

sealed interface UiState {
    data object Idle : UiState
    data object Listening : UiState
    data class Thinking(val heard: String) : UiState
    data class Confirm(val question: String) : UiState
    data class Working(val steps: List<String> = emptyList(), val watchUrl: String? = null) : UiState
    data class Done(val text: String, val failed: Boolean = false) : UiState
}

/**
 * The listen -> understand -> confirm -> act -> reply loop. Lives on the main thread.
 * Private mode (the default) understands and acts on the phone: rules, the on-device model and
 * OnDeviceAgent; only web questions go to the backend, for TinyFish. Cloud mode
 * (diplo.cloud=true) asks the backend first and lets Google ARTEMIS do phone tasks.
 */
class AgentController(context: Context, private val scope: CoroutineScope) {

    var state by mutableStateOf<UiState>(UiState.Idle)
        private set

    // For the screen's animations: mic loudness (0..1), what Diplo is saying and which word of
    // it, and the user's last words shown above the reply.
    var level by mutableFloatStateOf(0f)
        private set
    var spokenText by mutableStateOf<String?>(null)
        private set
    var spokenWord by mutableStateOf<IntRange?>(null)
        private set
    var heard by mutableStateOf("")
        private set

    private val speaker = Speaker(
        context,
        onSpeaking = { text ->
            spokenText = text
            spokenWord = null
        },
        onWord = { spokenWord = it },
    )
    private val appContext = context.applicationContext
    private val executor = PhoneExecutor(context)
    private val backend = BackendClient()
    private val agent = OnDeviceAgent(appContext, executor)
    private var appTask: Job? = null // OnDeviceAgent at work inside an app

    init {
        // Fetch the on-device model now, while there is a network, so it is there offline.
        scope.launch { LocalBrain.prepare(appContext) }
    }

    // "रोकें" in the notification, reachable while ARTEMIS has other apps in front.
    private val stopNotification = StopNotification(appContext) {
        stop()
        comeBack()
        speaker.say("ठीक है, रोक दिया।")
    }
    private val speech = SpeechInput(context, onText = ::onHeard, onFailure = ::onSpeechError, onLevel = { level = it })

    private var pending: AgentIntent? = null // waiting for "haan" / "nahi"
    private var clarifyContext: String? = null // earlier words when we asked a follow-up
    private var webRun: EventSource? = null
    private var runIntent: AgentIntent? = null // what the running web or phone task is doing

    // The last few (heard, said) pairs, so the backend can tell what "यह मैसेज" or "कौन जीता" means.
    private val history = ArrayDeque<Pair<String, String>>()
    private var turnText: String? = null // the command the current turn is answering

    fun startListening() {
        speaker.stop()
        // A second tap while listening stops: two live recognizers fail with ERROR_CLIENT, and the
        // first one then hears Diplo reading out that error.
        if (state == UiState.Listening) {
            speech.cancel()
            state = UiState.Idle
            return
        }
        state = UiState.Listening
        speech.listen()
    }

    /** Kill switch: the Stop button, saying "ruko", or "रोकें" in the notification. */
    fun stop() {
        if (runIntent?.params?.get("flow") == "phone_task") {
            // Closing the stream stops the task too, but the phone must stop even if the stream
            // was already lost, so tell the agent directly as well.
            scope.launch {
                try {
                    backend.stopPhone()
                } catch (e: IOException) {
                    Log.w(TAG, "could not reach the backend to stop the phone task: $e")
                }
            }
        }
        stopNotification.hide()
        appTask?.cancel()
        appTask = null
        webRun?.cancel()
        webRun = null
        runIntent = null
        pending = null
        clarifyContext = null
        turnText = null
        speech.cancel()
        speaker.stop()
        state = UiState.Idle
    }

    /** Typed instead of spoken: for noisy rooms, or when speech recognition isn't available. */
    fun submitText(text: String) {
        if (text.isBlank()) return
        speech.cancel()
        speaker.stop()
        onHeard(text.trim())
    }

    /** On-screen Haan / Nahi buttons. */
    fun answer(yes: Boolean) {
        val intent = pending ?: return
        pending = null
        speech.cancel()
        if (yes) execute(intent) else finish("ठीक है, नहीं किया।")
    }

    fun destroy() {
        stop()
        stopNotification.destroy()
        speech.destroy()
        speaker.shutdown()
    }

    private fun onHeard(text: String) {
        if (OfflineParser.isStop(text)) {
            stop()
            speaker.say("ठीक है, रुक गया।")
            return
        }
        pending?.let { intent ->
            when (OfflineParser.confirmation(text)) {
                true -> answer(yes = true)
                false -> answer(yes = false)
                null -> ask(intent.speak)
            }
            return
        }

        val fullText = clarifyContext?.takeIf { answersQuestion(text) }?.let { "$it $text" } ?: text
        clarifyContext = null
        turnText = fullText
        heard = text
        state = UiState.Thinking(text)
        scope.launch {
            val intent = (if (BuildConfig.CLOUD_MODE) understandInCloud(fullText) else understandOnPhone(fullText))
                ?: return@launch
            Log.i(TAG, "heard \"$fullText\" -> ${intent.kind} ${intent.action} ${intent.params}")
            handle(fullText, intent)
        }
    }

    // Private mode: nothing said leaves the phone, except a web question on its way to TinyFish.
    // The rules are instant; the on-device model takes a few seconds and only gets what they miss.
    private suspend fun understandOnPhone(text: String): AgentIntent =
        OfflineParser.parse(text) ?: AppTaskRules.parse(text) ?: WebRules.parse(text) ?: SmallTalk.reply(text)
            ?: LocalBrain.parse(appContext, text)
            ?: AgentIntent(AgentIntent.Kind.UNKNOWN, speak = "माफ़ कीजिए, मैं समझ नहीं पाया। फिर से बोलिए।")

    // Cloud mode: the backend's model decides, and with Google ARTEMIS on it does phone tasks.
    // Offline, the phone's rules, then the on-device model for what they miss.
    private suspend fun understandInCloud(text: String): AgentIntent? = try {
        backend.parseIntent(text, history.toList())
    } catch (e: IOException) {
        Log.w(TAG, "heard \"$text\": backend unreachable, understanding it on the phone: $e")
        OfflineParser.parse(text) ?: LocalBrain.parse(appContext, text) ?: run {
            finish("सर्वर से नहीं जुड़ पाया। कॉल, मैसेज और अलार्म इसके बिना भी चलते हैं।", failed = true)
            null
        }
    }

    // We asked for an ID, a PNR or a name. A long answer without digits is a new command
    // ("हमसफर ट्रेन का स्टेटस बताओ"), and gluing it on would keep asking the old question.
    private fun answersQuestion(text: String) =
        text.any(Char::isDigit) || text.trim().split(Regex("""\s+""")).size <= 4

    private fun handle(heard: String, intent: AgentIntent) {
        when {
            intent.kind == AgentIntent.Kind.CLARIFY -> {
                clarifyContext = heard
                remember(intent.speak) // the answer may come as a new sentence: "उसको इंट्रो लिखकर भेज दो"
                state = UiState.Thinking(heard)
                speaker.say(intent.speak) { startListening() }
            }
            intent.kind == AgentIntent.Kind.UNKNOWN -> finish(intent.speak, failed = true)
            intent.kind == AgentIntent.Kind.CHAT -> finish(intent.speak) // small talk: the reply is the answer
            // A task inside an app: the question repeats the user's own words, never the model's.
            intent.action == "app_task" && intent.speak.isEmpty() -> handle(
                heard,
                intent.copy(
                    speak = if (intent.needsConfirmation) "फ़ोन पर यह करूं: ${heard.trim().trimEnd('।', '.', '!', '?')}?"
                    else "ठीक है, कर रहा हूं।",
                ),
            )
            // Find the contact before asking, so "haan" confirms the person actually saved on the phone.
            intent.kind == AgentIntent.Kind.PHONE && intent.action != "app_task" &&
                "contact" in intent.params && "number" !in intent.params -> {
                val spoken = intent.params.getValue("contact")
                val contact = executor.findContact(spoken)
                Log.i(TAG, "contact \"$spoken\" -> ${contact?.name}")
                contact ?: return finish("${spoken.replaceFirstChar(Char::titlecase)} नाम का कोई कॉन्टैक्ट नहीं मिला।", failed = true)
                handle(
                    heard,
                    intent.copy(
                        params = intent.params + mapOf("contact" to contact.name, "number" to contact.number),
                        speak = intent.speak.replaceFirst(spoken, contact.name, ignoreCase = true),
                    ),
                )
            }
            // The backend's policy also asks, but the phone never trusts a reply to skip it.
            intent.needsConfirmation || intent.action in ALWAYS_ASK -> {
                pending = intent
                ask(intent.speak)
            }
            else -> execute(intent)
        }
    }

    private fun ask(question: String) {
        Log.i(TAG, "asked: $question")
        state = UiState.Confirm(question)
        speaker.say(question) { if (pending != null) speech.listen() }
    }

    private fun execute(intent: AgentIntent) {
        when {
            intent.kind == AgentIntent.Kind.WEB -> runWeb(intent)
            intent.action == "app_task" -> runAppTask(intent)
            else -> finish(executor.execute(intent))
        }
    }

    // A task inside an app, done on the phone by OnDeviceAgent: nothing seen leaves it. The app
    // stays in front at the end, showing the result, and Diplo says how it went.
    private fun runAppTask(intent: AgentIntent) {
        state = UiState.Working()
        speaker.say("ठीक है, कर रहा हूं।")
        stopNotification.show(heard)
        appTask = scope.launch {
            val outcome = agent.run(intent.params) { step ->
                Log.i(TAG, "agent: $step")
                (state as? UiState.Working)?.let { state = it.copy(steps = it.steps + step) }
            }
            appTask = null
            stopNotification.hide()
            finish(outcome.speak, failed = !outcome.ok)
        }
    }

    private fun runWeb(intent: AgentIntent) {
        val flow = intent.params["flow"] ?: return finish("यह काम समझ नहीं आया।", failed = true)
        state = UiState.Working()
        // web_search is a quick read of public pages; portal flows drive a real browser, and
        // phone_task has Google ARTEMIS tap through this phone's apps.
        speaker.say(
            when (flow) {
                "web_search" -> "वेब पर देख रहा हूं।"
                "phone_task" -> "फ़ोन पर कर रहा हूं, थोड़ा समय लगेगा।"
                else -> "पोर्टल खोल रहा हूं, थोड़ा समय लगेगा।"
            },
        )
        runIntent = intent
        if (flow == "phone_task") stopNotification.show(heard)
        var source: EventSource? = null
        source = backend.runWebTask(flow, intent.params - "flow") { event ->
            // Hop to the main thread and drop events from a run the user already stopped.
            scope.launch { if (webRun === source) onWebEvent(event) }
        }
        webRun = source
    }

    private fun onWebEvent(event: WebEvent) {
        val working = state as? UiState.Working
        when (event) {
            is WebEvent.Progress -> if (working != null) state = working.copy(steps = working.steps + event.message)
            is WebEvent.Watch -> if (working != null) state = working.copy(watchUrl = event.url)
            is WebEvent.Result -> {
                webRun = null
                stopNotification.hide()
                val run = runIntent
                runIntent = null
                if (run?.params?.get("flow") == "phone_task") sayPhoneResult(run, event.speak) else finish(event.speak)
            }
            is WebEvent.Error -> {
                webRun = null
                stopNotification.hide()
                if (runIntent?.params?.get("flow") == "phone_task") comeBack()
                runIntent = null
                finish(event.speak, failed = true)
            }
        }
    }

    // ARTEMIS only reports that it finished; the screen it left shows what happened (a fare, a
    // count, a sent message), so read it and let the backend's model say the result.
    private fun sayPhoneResult(run: AgentIntent, fallback: String) {
        val screen = DiploAccessibilityService.instance?.readScreen(maxItems = 80).orEmpty()
        comeBack() // after reading: the screen to read is the app ARTEMIS left open
        scope.launch {
            val result = try {
                backend.answerFromScreen(run.params["goal"].orEmpty(), heard, screen)
            } catch (e: IOException) {
                fallback
            }
            if (state is UiState.Working) finish(result) // not if the user said "ruko" meanwhile
        }
    }

    // ARTEMIS works in other apps, so Diplo is behind them by the end, which looks like it closed.
    // Its bound accessibility service lets it come back to the front from the background.
    private fun comeBack() {
        val front = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            appContext.startActivity(front)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "could not come back to the front: $e")
        } catch (e: SecurityException) {
            Log.w(TAG, "could not come back to the front: $e")
        }
    }

    private fun onSpeechError(code: Int) {
        // Only errors from a live listen matter: cancel() (Stop, or typing instead) also reports one.
        if (state != UiState.Listening && pending == null) return
        pending?.let {
            state = UiState.Confirm(it.speak) // whatever went wrong, keep the Haan / Nahi buttons up
            return
        }
        // Restarting the recognizer, not the user, causes these.
        if (code == SpeechRecognizer.ERROR_CLIENT || code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            state = UiState.Idle
            return
        }
        finish(
            when (code) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "कुछ सुनाई नहीं दिया। माइक दबाकर फिर बोलिए।"
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    "इस फ़ोन पर हिंदी में सुनना चालू नहीं है। Google ऐप में हिंदी स्पीच पैक डाउनलोड करें, या नीचे लिखिए।"
                else -> "माइक में दिक्कत है ($code)। नीचे लिखकर भी बता सकते हैं।"
            },
            failed = true,
        )
    }

    private fun finish(text: String, failed: Boolean = false) {
        Log.i(TAG, "said: $text")
        speech.cancel() // never listen while speaking: the mic would hear Diplo
        // A follow-up question that ended (e.g. nothing heard) must not glue onto the next command.
        clarifyContext = null
        if (failed) turnText = null else remember(text)
        state = UiState.Done(text, failed)
        speaker.say(text)
    }

    private fun remember(said: String) {
        val heard = turnText ?: return
        turnText = null
        history.addLast(heard to said)
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    private companion object {
        const val TAG = "Diplo"
        const val MAX_HISTORY = 3
        val ALWAYS_ASK = setOf("call", "send_message", "save_contact", "delete_contact")
    }
}
