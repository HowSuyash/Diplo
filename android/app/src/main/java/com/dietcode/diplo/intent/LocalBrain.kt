package com.dietcode.diplo.intent

import ai.runanywhere.proto.v1.InferenceFramework
import ai.runanywhere.proto.v1.ModelCategory
import android.content.Context
import android.util.Log
import com.dietcode.diplo.BuildConfig
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
 * The on-device LLM, for what OfflineParser's rules miss when there is no network.
 * RunAnywhere runs a small GGUF model with llama.cpp on the phone, and it answers in the
 * backend's Intent JSON (docs/API_CONTRACT.md). Phone actions and small talk only: web flows
 * need the network anyway.
 *
 * Order in the app: OfflineParser (instant) -> backend /intent when online -> LocalBrain.
 */
object LocalBrain {

    /** diplo.localModel and diplo.localModelUrl in android/local.properties pick another GGUF model. */
    val model: String = BuildConfig.LOCAL_MODEL

    private val starting = Mutex()

    @Volatile
    private var started = false

    @Volatile
    private var loaded = false

    // A short context keeps memory small. (Thread count can't be set: RunAnywhere 0.20.27 rejects
    // LoadOptions.threads with "cannot be carried by the native load ABI yet".)
    private val loadOptions = LoadOptions(contextLength = 1024)

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
            val options = LlmOptions(model = model, maxOutputTokens = 96, temperature = 0f, systemPrompt = SYSTEM_PROMPT)
            val reply = RunAnywhere.llm.generate(prompt, options)
            Log.i(TAG, "on-device \"$text\" -> ${reply.text} (${reply.tokensPerSecond} tok/s)")
            fromReply(reply.text)
        } catch (e: SDKException) {
            Log.w(TAG, "on-device model failed: $e") // no model yet (it downloads while online), or no memory
            null
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
            RunAnywhere.models.load(model, loadOptions)
            loaded = true
        }
    }

    // Short on purpose: on a phone CPU every prompt token costs time before the first word
    // (428 tokens took ~14 s on a moto g64).
    private const val SYSTEM_PROMPT = """Turn a spoken phone command (Hindi, Hinglish or English) into JSON:
{"kind":"phone"|"chat"|"unknown","action":str,"params":{},"speak":str}
Actions: call {contact}; send_message {contact,message,channel:"whatsapp"|"sms"}; set_alarm {hour:0-23,minute}; read_screen {}; open_app {app,query}; set_setting {setting:brightness|volume|torch|dnd|silent|font_size|wifi|bluetooth|mobile_data|location, value:up|down|auto|on|off|max|min|mute}; save_contact {name,number}; delete_contact {contact}.
Complaints mean the fix: "sunai nahi deta" -> volume up, "aankhon mein chubh raha hai" -> brightness down, "shor" -> silent on.
Small talk: kind "chat", a short reply in Hindi (Devanagari) in "speak". Else kind "unknown". JSON only.
"papa ko phone lagao" -> {"kind":"phone","action":"call","params":{"contact":"papa"},"speak":""}"""

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
        return when (json.optString("kind").lowercase()) {
            "chat" -> json.optString("speak").trim().takeIf { it.isNotEmpty() }
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
