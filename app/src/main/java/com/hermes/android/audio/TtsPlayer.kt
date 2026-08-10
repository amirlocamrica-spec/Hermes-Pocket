package com.hermes.android.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Plays assistant replies aloud using the Android TextToSpeech engine.
 *
 * Wraps the TTS engine lifecycle: [initialize] must be called once (e.g. from
 * the chat screen), [speak] queues text, [shutdown] releases the engine.
 */
class TtsPlayer(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingSpeech: String? = null
    private var onDone: (() -> Unit)? = null

    /** Initializes the engine. Call once before using [speak]. */
    fun initialize(onReady: (() -> Unit)? = null) {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                if (engine != null) {
                    // Prefer Persian if available, else default locale.
                    val result = engine.setLanguage(Locale("fa", "IR"))
                    if (result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        engine.setLanguage(Locale.getDefault())
                    }
                    ready = true
                    pendingSpeech?.let { text ->
                        pendingSpeech = null
                        speak(text)
                    }
                    onReady?.invoke()
                }
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                onDone?.invoke()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onDone?.invoke()
            }
        })
    }

    /** Speaks [text]. If the engine isn't ready yet, queues it. */
    fun speak(text: String, speed: Float = 1.0f, done: (() -> Unit)? = null) {
        if (text.isBlank()) return
        onDone = done
        val engine = tts
        if (!ready || engine == null) {
            pendingSpeech = text
            initialize()
            return
        }
        try {
            engine.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes_tts_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            // TTS can throw on shutdown races; treat as silent failure.
            done?.invoke()
        }
    }

    /** Stops any in-progress speech. */
    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    /** Whether the engine is ready and speaking is possible. */
    val isReady: Boolean get() = ready && tts != null

    /** Releases the engine. Call from onDestroy of the owning screen. */
    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
    }
}
