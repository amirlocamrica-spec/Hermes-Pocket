package com.hermes.android.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Voice input (STT) — Triad-style "speak instead of typing".
 *
 * Wraps Android's [SpeechRecognizer]. The caller owns the lifecycle:
 * create once per screen, call [start] on tap, [destroy] on dispose.
 * Results arrive through [onResult] (final text) and [onError].
 */
class SpeechToTextHelper(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit = {},
    private val onListeningChanged: (Boolean) -> Unit = {},
) {
    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Default language: Persian if the device is fa, otherwise English. */
    private fun language(): String {
        val locale = Locale.getDefault().language
        return if (locale == "fa") "fa-IR" else "en-US"
    }

    fun start() {
        destroyInternal()
        if (!isAvailable) {
            onError("Speech recognition not available on this device")
            return
        }
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                onListeningChanged(false)
                if (text.isNotBlank()) onResult(text)
            }

            override fun onPartialResults(partialResults: Bundle) {
                // Keep the interim hypothesis so the UI can show live text.
                val matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onResult)
            }

            override fun onError(error: Int) {
                onListeningChanged(false)
                onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — try again"
                        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
                        else -> "Recognition error ($error)"
                    },
                )
            }

            override fun onBeginningOfSpeech() { onListeningChanged(true) }
            override fun onEndOfSpeech() { onListeningChanged(false) }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            onListeningChanged(false)
            onError("Could not start voice input: ${e.message}")
        }
    }

    fun stop() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
    }

    fun destroy() = destroyInternal()

    private fun destroyInternal() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }
}