package com.dietcode.diplo.net

import com.dietcode.diplo.BuildConfig
import com.dietcode.diplo.intent.AgentIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Events from POST /web/run, see docs/API_CONTRACT.md. */
sealed interface WebEvent {
    data class Progress(val message: String) : WebEvent
    data class Watch(val url: String) : WebEvent
    data class Result(val data: JSONObject, val speak: String) : WebEvent
    data class Error(val message: String, val speak: String) : WebEvent

    companion object {
        fun fromJson(json: JSONObject): WebEvent = when (json.optString("type")) {
            "progress" -> Progress(json.optString("message"))
            "watch" -> Watch(json.optString("url"))
            "result" -> Result(json.optJSONObject("data") ?: JSONObject(), json.optString("speak"))
            else -> Error(json.optString("message"), json.optString("speak", "कुछ गड़बड़ हो गई।"))
        }
    }
}

class BackendClient(private val baseUrl: String = BuildConfig.BACKEND_URL) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Web runs can take a minute or more; the backend keeps the stream open until the result.
    private val streaming = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()

    /** [history] is the last few (user said, Diplo said) pairs, oldest first. */
    suspend fun parseIntent(text: String, history: List<Pair<String, String>> = emptyList()): AgentIntent =
        withContext(Dispatchers.IO) {
            val turns = history.map { (user, diplo) -> JSONObject().put("user", user).put("diplo", diplo) }
            val body = JSONObject().put("text", text).put("history", JSONArray(turns)).toString().toRequestBody(json)
            val request = Request.Builder().url("$baseUrl/intent").post(body).build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                AgentIntent.fromJson(JSONObject(resp.body?.string().orEmpty()))
            }
        }

    /** After a phone_task: what to say, read from the [screen] text the agent left (POST /phone/answer). */
    suspend fun answerFromScreen(goal: String, said: String, screen: List<String>): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("goal", goal).put("said", said).put("screen", JSONArray(screen))
                .toString().toRequestBody(json)
            val request = Request.Builder().url("$baseUrl/phone/answer").post(body).build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                JSONObject(resp.body?.string().orEmpty()).getString("speak")
            }
        }

    /** Streams a web flow. [onUpdate] runs on an OkHttp thread; cancel the returned source to stop. */
    fun runWebTask(flow: String, params: Map<String, String>, onUpdate: (WebEvent) -> Unit): EventSource {
        val body = JSONObject().put("flow", flow).put("params", JSONObject(params)).toString().toRequestBody(json)
        val request = Request.Builder().url("$baseUrl/web/run").post(body).build()
        return EventSources.createFactory(streaming).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                onUpdate(WebEvent.fromJson(JSONObject(data)))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                onUpdate(WebEvent.Error(t?.message ?: "HTTP ${response?.code}", "वेब एजेंट से कनेक्शन टूट गया।"))
            }
        })
    }
}
