package com.martin.speechtranslator.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred

/**
 * One TextToSpeech instance that speaks Japanese or English depending on the translation.
 */
class TtsManager(context: Context) {

    private val initialized = CompletableDeferred<Boolean>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var utteranceCounter = 0
    private var onDone: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        initialized.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            // WHY: these callbacks arrive on a binder thread — hop to main before touching state.
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                mainHandler.post { onDone?.invoke() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post { onDone?.invoke() }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post { onDone?.invoke() }
            }
        })
    }

    /**
     * Speaks [text] in Japanese or US English. [onFinished] runs on the main thread when the
     * utterance completes (or immediately if TTS is unavailable / the language is missing).
     */
    suspend fun speak(text: String, japanese: Boolean, onFinished: () -> Unit) {
        if (!initialized.await()) {
            onFinished()
            return
        }
        val result = tts.setLanguage(if (japanese) Locale.JAPANESE else Locale.US)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            onFinished()
            return
        }
        onDone = onFinished
        val id = "utt-${utteranceCounter++}"
        val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (queued != TextToSpeech.SUCCESS) onFinished()
    }

    fun stop() {
        onDone = null
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
