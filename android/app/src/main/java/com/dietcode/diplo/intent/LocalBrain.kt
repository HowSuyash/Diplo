package com.dietcode.diplo.intent

import ai.runanywhere.proto.v1.InferenceFramework
import ai.runanywhere.proto.v1.ModelCategory
import android.content.Context
import android.util.Log
import com.dietcode.diplo.BuildConfig
import com.dietcode.diplo.exec.AgentPlan
import com.dietcode.diplo.exec.AgentStep
import com.dietcode.diplo.exec.ScreenElement
import com.runanywhere.sdk.foundation.errors.SDKException
import com.runanywhere.sdk.llm.llamacpp.LlamaCPP
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * The on-device LLM. RunAnywhere runs a GGUF model with llama.cpp on the phone, so nothing said
 * or seen leaves it. In private mode it is the on-device agent's planner and operator
 * ([plan], [act]; [com.dietcode.diplo.exec.OnDeviceAgent]). In cloud mode, when the backend is
 * unreachable, [parse] turns a command into the backend's Intent JSON (docs/API_CONTRACT.md).
 */
object LocalBrain {

    /** diplo.localModel and diplo.localModelUrl in android/local.properties pick another GGUF model. */
    val model: String = BuildConfig.LOCAL_MODEL

    private val starting = Mutex()

    @Volatile
    private var started = false

    @Volatile
    private var loaded = false

    // A short context keeps memory small: the agent's prompts stay under ~800 tokens. (Thread count
    // can't be set: RunAnywhere 0.20.27 rejects LoadOptions.threads with "cannot be carried by the
    // native load ABI yet".) diplo.localAccel=gpu or auto tries the phone's GPU.
    private val loadOptions = LoadOptions(
        accelerator = when (BuildConfig.LOCAL_ACCEL) {
            "gpu" -> AcceleratorPolicy.GPU
            "auto" -> AcceleratorPolicy.AUTO
            else -> AcceleratorPolicy.CPU
        },
        contextLength = 1536,
    )

    /** Starts the SDK and, while there is a network, downloads the model once. False if that failed. */
    suspend fun prepare(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            start(context)
            val path = RunAnywhere.models.get(model)?.local_path.orEmpty()
            if (path.isEmpty() || !File(path).exists()) {
                RunAnywhere.models.download(model).collect { event ->
                    if (event is DownloadEvent.Completed) Log.i(TAG, "on-device model $model downloaded")
                }
            }
            load() // now, so the first offline command doesn't wait for it
            true
        } catch (e: SDKException) {
            Log.w(TAG, "on-device model $model not ready: $e")
            false
        }
    }

    /** The intent for [text], or null when the model is missing or doesn't understand. */
    suspend fun parse(context: Context, text: String): AgentIntent? = withContext(Dispatchers.Default) {
        try {
            start(context)
            load()
            // Qwen3 thinks before answering unless told not to; a voice command can't wait for that.
            val prompt = if (model.startsWith("qwen3")) "$text /no_think" else text
            val options = LlmOptions(model = model, maxOutputTokens = 120, temperature = 0f, systemPrompt = SYSTEM_PROMPT)
            val reply = RunAnywhere.llm.generate(prompt, options)
            Log.i(TAG, "on-device \"$text\" -> ${reply.text} (${reply.tokensPerSecond} tok/s)")
            fromReply(reply.text)
        } catch (e: SDKException) {
            Log.w(TAG, "on-device model failed: $e") // no model yet (it downloads while online), or no memory
            null
        }
    }

    /**
     * The planner, as Google ARTEMIS plans before acting: a reply, a question for what is missing,
     * or the app, goal and steps of a task. Null when the model can't run or makes no sense.
     */
    suspend fun plan(context: Context, request: String): AgentPlan? = withContext(Dispatchers.Default) {
        try {
            start(context)
            load()
            val prompt = if (model.startsWith("qwen3")) "$request /no_think" else request
            val options = LlmOptions(model = model, maxOutputTokens = 160, temperature = 0f, systemPrompt = PLAN_PROMPT)
            val started = System.currentTimeMillis()
            val reply = RunAnywhere.llm.generate(prompt, options)
            Log.i(TAG, "plan \"$request\" -> ${reply.text.trim()} (${System.currentTimeMillis() - started} ms)")
            AgentPlan.parse(reply.text)
        } catch (e: SDKException) {
            Log.w(TAG, "on-device plan failed: $e")
            null
        }
    }

    /**
     * The operator: the next action on this [screen] toward [goal], following [steps], knowing
     * what was [done]. [note] tells it when its last action changed nothing. Stuck if the model
     * can't run.
     */
    suspend fun act(
        context: Context,
        goal: String,
        steps: List<String>,
        app: String,
        screen: List<ScreenElement>,
        done: List<String>,
        note: String?,
    ): AgentStep = withContext(Dispatchers.Default) {
        try {
            start(context)
            load()
            val prompt = buildString {
                appendLine("Goal: $goal")
                if (steps.isNotEmpty()) appendLine("Plan: ${steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString(" ")}")
                // The last few only: every word costs prompt time on a phone CPU.
                appendLine("Done so far: ${done.takeLast(DONE_SHOWN).joinToString("; ").ifEmpty { "nothing yet" }}")
                note?.let(::appendLine)
                appendLine("App: $app")
                appendLine("Screen:")
                screen.forEach { appendLine(it.line()) }
                if (model.startsWith("qwen3")) append("/no_think")
            }
            val options = LlmOptions(model = model, maxOutputTokens = 60, temperature = 0f, systemPrompt = ACT_PROMPT)
            val started = System.currentTimeMillis()
            val reply = RunAnywhere.llm.generate(prompt, options)
            Log.i(TAG, "act for \"$goal\" -> ${reply.text.trim()} (${System.currentTimeMillis() - started} ms)")
            AgentStep.parse(reply.text)
        } catch (e: SDKException) {
            Log.w(TAG, "on-device act failed: $e")
            AgentStep.Stuck
        }
    }

    private suspend fun start(context: Context) {
        if (started) return
        starting.withLock {
            if (started) return
            RunAnywhere.initialize(context.applicationContext, "", "") // no API key: local only
            LlamaCPP.register()
            // Without a key the catalog is empty ("Model not found: qwen3-0.6b"), so name the file ourselves.
            RunAnywhere.models.register(
                ModelRegistration.url(
                    name = model,
                    url = BuildConfig.LOCAL_MODEL_URL,
                    framework = InferenceFramework.INFERENCE_FRAMEWORK_LLAMA_CPP,
                    category = ModelCategory.MODEL_CATEGORY_LANGUAGE,
                    id = model,
                ),
            )
            started = true
        }
    }

    private suspend fun load() {
        if (loaded) return
        starting.withLock {
            if (loaded) return
            val started = System.currentTimeMillis()
            RunAnywhere.models.load(model, loadOptions)
            Log.i(TAG, "on-device model $model loaded on ${BuildConfig.LOCAL_ACCEL} in ${System.currentTimeMillis() - started} ms")
            loaded = true
        }
    }

    // Short on purpose: on a phone CPU every prompt token costs time before the first word
    // (428 tokens took ~14 s on a moto g64).
    private const val SYSTEM_PROMPT = """Turn a spoken phone command (Hindi, Hinglish or English) into JSON:
{"kind":"phone"|"app"|"web"|"chat"|"unknown","action":str,"params":{},"speak":str}
phone actions: call {contact}; send_message {contact,message,channel:"whatsapp"|"sms"}; set_alarm {hour:0-23,minute}; open_app {app,query}; set_setting {setting:brightness|volume|torch|dnd|silent|font_size, value:up|down|auto|on|off|max|min|mute}.
Complaints mean the fix: "sunai nahi deta" -> volume up, "aankhon mein chubh raha hai" -> brightness down.
app, action app_task: anything else done inside an app: {app, goal: short English task, target: the button or setting to reach, destination, query}. Rides use the cab app the user names.
web, action web_search: a question about facts, news, weather or schemes: {query}.
Small talk: kind "chat", a short reply in Hindi (Devanagari) in "speak". Else kind "unknown". JSON only.
"papa ko phone lagao" -> {"kind":"phone","action":"call","params":{"contact":"papa"}}
"dark mode on karo" -> {"kind":"app","action":"app_task","params":{"app":"Settings","goal":"Turn on Dark theme","target":"Dark theme"}}
"rapido se abes college ke liye bike book karo" -> {"kind":"app","action":"app_task","params":{"app":"Rapido","goal":"Book a bike ride","destination":"ABES College"}}"""

    private const val DONE_SHOWN = 6

    // The planner and the operator of the on-device agent (OnDeviceAgent), ARTEMIS's two roles.
    // No app has a script: the examples only show the shape of a plan.
    private const val PLAN_PROMPT = """You are Diplo, a phone assistant for Indian users. Read the user's request (Hindi, Hinglish or English) and reply with JSON only, one of:
{"reply":"short answer in Hindi (Devanagari)"} for greetings, chat, or anything not done on the phone.
{"ask":"question in Hindi"} when the task needs a detail the user did not give, like where to go.
{"app":"app to open","goal":"the task in English with every detail the user gave","steps":["step", ...]} for anything done on the phone. Phone settings are in the Settings app.
Examples:
"namaste kaise ho" -> {"reply":"नमस्ते! मैं ठीक हूं। बताइए, क्या करूं?"}
"ola book karo" -> {"ask":"कहां जाना है?"}
"rapido se abes college ke liye bike book karo" -> {"app":"Rapido","goal":"Book a bike ride to ABES College","steps":["Open the destination search","Type ABES College","Pick the matching place","Choose Bike","Book the ride"]}
"dark mode on karo" -> {"app":"Settings","goal":"Turn on Dark theme","steps":["Open Display","Turn on the Dark theme switch"]}"""

    private const val ACT_PROMPT = """You operate an Android phone for the user, one action per turn, until the goal is reached.
Reply with one JSON action only:
{"do":"tap","n":4} tap element 4
{"do":"type","n":2,"text":"words"} type into input 2
{"do":"scroll"} see more of the page; {"do":"back"}; {"do":"wait"} while the screen loads
{"do":"open","app":"Name"} open another app
{"do":"done","say":"what you did, or the answer the user asked for, in short Hindi"} once the goal is reached
{"do":"ask","say":"question in Hindi"} only when the user must choose
Follow the plan and use the numbers shown. If your last action changed nothing, do something else. Never tap a button that pays money."""

    /** Whether a task acts for the user (books, sends, calls, pays), so it needs "haan" first. */
    fun actsForUser(goal: String) = risky.containsMatchIn(goal)

    // Tasks that act for the user ask "haan" first; looking at or changing a setting doesn't.
    private val risky = Regex(
        """\b(book|order|send|call|pay|buy|delete|post|request|transfer|share|upload|message|reply)\b""",
        RegexOption.IGNORE_CASE,
    )

    // What Diplo says before acting: our wording, never the model's (backend apply_policy does the same).
    private val speakTemplates = mapOf(
        "call" to "{contact} को कॉल करूं?",
        "send_message" to "{contact} को यह मैसेज भेजूं: {message}?",
        "set_alarm" to "{hour} बजकर {minute} मिनट का अलार्म लगा रहा हूं।",
        "read_screen" to "स्क्रीन पढ़ रहा हूं।",
        "open_app" to "{app} खोल रहा हूं।",
        "set_setting" to "ठीक है।",
        "save_contact" to "{name}, नंबर {number}, कॉन्टैक्ट में सेव करूं?",
        "delete_contact" to "{contact} का कॉन्टैक्ट डिलीट करूं? यह वापस नहीं आएगा।",
    )
    private val sensitive = setOf("call", "send_message", "save_contact", "delete_contact")
    private const val TAG = "Diplo"
    private val placeholder = Regex("""\{(\w+)\}""")
    private val thinking = Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL)

    /** The model's reply as a safe intent: known phone actions with our wording, or a chat reply. */
    internal fun fromReply(reply: String): AgentIntent? {
        val text = reply.replace(thinking, "")
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = try {
            JSONObject(text.substring(start, end + 1))
        } catch (e: JSONException) {
            return null
        }
        val action = json.optString("action")
        val raw = json.optJSONObject("params")
        // optString, not getString: small models send numbers ("hour": 6), and it reads them as text.
        val params = raw?.keys()?.asSequence()?.associateWith { raw.optString(it).trim() }
            ?.filterValues { it.isNotEmpty() } ?: emptyMap()
        val kind = json.optString("kind").lowercase()
        // A task inside an app: the on-device agent plans and does it (and asks "haan" itself).
        // Small models sometimes label it kind "phone".
        if (kind == "app" || action == "app_task") {
            val goal = params["goal"].orEmpty().ifBlank { return null }
            return AgentIntent(AgentIntent.Kind.PHONE, "agent_task", mapOf("request" to goal))
        }
        return when (kind) {
            "web" -> params["query"]?.let(WebRules::search)
            // The 0.6B model also says kind "small talk" or "greeting" for chat.
            "chat", "small talk", "small_talk", "smalltalk", "greeting" -> json.optString("speak").trim().takeIf { it.isNotEmpty() }
                ?.let { AgentIntent(AgentIntent.Kind.CHAT, speak = it) }
            "phone" -> {
                val template = speakTemplates[action] ?: return null
                // A written message ends in "।", and "...हूँ।?" reads badly in the question.
                val shown = params.mapValues { (k, v) -> if (k == "message") v.trimEnd('।', '.', '!', '?', ' ') else v }
                var complete = true
                val speak = template.replace(placeholder) { m -> shown[m.groupValues[1]] ?: "".also { complete = false } }
                if (!complete) return null
                AgentIntent(AgentIntent.Kind.PHONE, action, params, needsConfirmation = action in sensitive, speak = speak)
            }
            else -> null
        }
    }
}
