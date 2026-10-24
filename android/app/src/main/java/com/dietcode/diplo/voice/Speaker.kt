package com.dietcode.diplo.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hindi text-to-speech. [say] can call back when speech ends, e.g. to start listening for "haan".
 * [onSpeaking] gets the text being spoken (null when quiet) and [onWord] the word being said
 * now, so the screen can animate along. All callbacks run on the main thread.
 */
class Speaker(
    context: Context,
    private val onSpeaking: (String?) -> Unit = {},
    private val onWord: (IntRange) -> Unit = {},
) : TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, () -> Unit>()
    private val texts = ConcurrentHashMap<String, String>()
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.forLanguageTag("hi-IN")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                texts[utteranceId]?.let { text -> main.post { onSpeaking(text) } }
            }

            override fun onDone(utteranceId: String?) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)

            // Flushed by a newer say(): drop it, and don't run its callback.
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                texts.remove(utteranceId)
                pending.remove(utteranceId)
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                main.post { onWord(start until end) }
            }
        })
        ready = true
    }

    private fun finish(utteranceId: String?) {
        texts.remove(utteranceId)
        main.post { if (texts.isEmpty()) onSpeaking(null) }
        pending.remove(utteranceId)?.let { main.post(it) }
    }

    fun say(text: String, onDone: (() -> Unit)? = null) {
        if (!ready || text.isBlank()) {
            onDone?.let { main.post(it) }
            return
        }
        val id = UUID.randomUUID().toString()
        if (onDone != null) pending[id] = onDone
        texts[id] = text
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        pending.clear()
        texts.clear()
        tts.stop()
        main.post { onSpeaking(null) }
    }

    fun shutdown() = tts.shutdown()
}
