package com.dietcode.saarthi.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Hindi text-to-speech. [say] can call back when speech ends, e.g. to start listening for "haan". */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, () -> Unit>()
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.forLanguageTag("hi-IN")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
        })
        ready = true
    }

    private fun finish(utteranceId: String?) {
        pending.remove(utteranceId)?.let { main.post(it) }
    }

    fun say(text: String, onDone: (() -> Unit)? = null) {
        if (!ready || text.isBlank()) {
            onDone?.let { main.post(it) }
            return
        }
        val id = UUID.randomUUID().toString()
        if (onDone != null) pending[id] = onDone
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        pending.clear()
        tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
