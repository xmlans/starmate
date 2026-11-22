package org.xmyun.starmate.feature.voice.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Android 自带 TTS，用 synthesizeToFile 合成 WAV。只能真机验证。 */
@Singleton
class SystemTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : TtsEngine {

    override val model: String = "android-tts"
    override val format: String = "wav"

    private val mutex = Mutex()
    private var tts: TextToSpeech? = null

    override suspend fun synthesize(text: String, tone: String?, target: File): TtsResult = mutex.withLock {
        try {
            val engine = tts ?: init()?.also { tts = it } ?: return@withLock TtsResult.Failure("system tts unavailable")
            target.parentFile?.mkdirs()
            val ok = withTimeoutOrNull(SYNTH_TIMEOUT_MS) { synthesizeOnce(engine, text, target) } ?: false
            if (ok && target.length() > 0) {
                TtsResult.Success
            } else {
                target.delete()
                TtsResult.Failure(if (ok) "empty file" else "system tts error")
            }
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: Exception) {
            target.delete()
            TtsResult.Failure(e.javaClass.simpleName)
        }
    }

    private suspend fun init(): TextToSpeech? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(INIT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val holder = arrayOfNulls<TextToSpeech>(1)
                holder[0] = TextToSpeech(context) { status ->
                    val engine = holder[0]
                    if (status == TextToSpeech.SUCCESS && engine != null) {
                        val locale = Locale.getDefault()
                        if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) engine.language = locale
                        if (cont.isActive) cont.resume(engine)
                    } else {
                        engine?.shutdown()
                        if (cont.isActive) cont.resume(null)
                    }
                }
                cont.invokeOnCancellation { holder[0]?.shutdown() }
            }
        }
    }

    private suspend fun synthesizeOnce(engine: TextToSpeech, text: String, target: File): Boolean =
        suspendCancellableCoroutine { cont ->
            val utteranceId = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit

                override fun onDone(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == utteranceId && cont.isActive) cont.resume(false)
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && cont.isActive) cont.resume(false)
                }
            })
            val result = engine.synthesizeToFile(text, Bundle(), target, utteranceId)
            if (result != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
            cont.invokeOnCancellation { engine.stop() }
        }

    private companion object {
        const val INIT_TIMEOUT_MS = 10_000L
        const val SYNTH_TIMEOUT_MS = 60_000L
    }
}
