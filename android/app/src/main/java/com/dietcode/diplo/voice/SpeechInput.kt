package com.dietcode.diplo.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Push-to-talk speech-to-text using Android's recognizer (hi-IN handles Hindi and Hinglish).
 * Works offline once the Hindi offline speech pack is downloaded on the phone.
 * Must be created and used on the main thread.
 */
class SpeechInput(
    context: Context,
    private val onText: (String) -> Unit,
    private val onFailure: (Int) -> Unit,
    private val onLevel: (Float) -> Unit = {}, // 0..1 loudness while listening, for the orb
) {
    private var preferOffline = true
    private var languageTag = "hi-IN"

    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
        setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                onLevel(0f)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) onFailure(SpeechRecognizer.ERROR_NO_MATCH) else onText(text)
            }

            override fun onError(error: Int) {
                onLevel(0f)
                // No offline Hindi pack on this phone: use the online recognizer from now on.
                val noLanguage = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                if (noLanguage && preferOffline) {
                    preferOffline = false
                    listen(languageTag)
                } else {
                    onFailure(error)
                }
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            // Roughly -2 (silence) to 10 dB (speaking up close).
            override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onLevel(0f)
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    fun listen(languageTag: String = "hi-IN") {
        this.languageTag = languageTag
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        }
        recognizer.cancel() // end any session still open, so this one is the only one
        recognizer.startListening(intent)
    }

    fun cancel() = recognizer.cancel()

    fun destroy() = recognizer.destroy()
}
