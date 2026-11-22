package org.xmyun.starmate.feature.voice.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 系统 SpeechRecognizer。它自己录音，说完会自动停下（这时回调 onEnded）。
 * SpeechRecognizer 只能在主线程使用。只能真机验证。
 */
class SystemSttEngine(private val context: Context) : SttEngine {

    private var recognizer: SpeechRecognizer? = null
    private var result = CompletableDeferred<SttResult>()

    override suspend fun start(onEnded: () -> Unit): Boolean = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext false
        val outcome = CompletableDeferred<SttResult>()
        result = outcome
        val r = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            return@withContext false
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                if (outcome.complete(SttResult.Failure(mapError(error)))) onEnded()
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                val value = if (text.isNullOrEmpty()) SttResult.Failure(SttFailure.NO_SPEECH) else SttResult.Text(text)
                if (outcome.complete(value)) onEnded()
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            r.destroy()
            return@withContext false
        }
        recognizer = r
        true
    }

    override suspend fun finish(): SttResult = withContext(Dispatchers.Main) {
        val r = recognizer ?: return@withContext SttResult.Failure(SttFailure.UNAVAILABLE)
        if (!result.isCompleted) r.stopListening()
        val value = withTimeoutOrNull(RESULT_TIMEOUT_MS) { result.await() } ?: SttResult.Failure(SttFailure.UNKNOWN)
        r.destroy()
        recognizer = null
        value
    }

    override fun cancel() {
        val r = recognizer ?: return
        recognizer = null
        result.cancel()
        // cancel 可能在任意线程调用，销毁放回主线程
        android.os.Handler(context.mainLooper).post {
            r.cancel()
            r.destroy()
        }
    }

    private fun mapError(error: Int): SttFailure = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SttFailure.NO_SPEECH
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> SttFailure.NETWORK
        SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SttFailure.RECORDER
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SttFailure.UNAVAILABLE
        else -> SttFailure.UNKNOWN
    }

    private companion object {
        const val RESULT_TIMEOUT_MS = 10_000L
    }
}
