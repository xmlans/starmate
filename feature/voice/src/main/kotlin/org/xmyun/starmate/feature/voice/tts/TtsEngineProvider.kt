package org.xmyun.starmate.feature.voice.tts

import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.xmyun.starmate.core.model.EndpointConfig
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ModelRouter
import org.xmyun.starmate.feature.voice.VoiceHttp
import org.xmyun.starmate.feature.voice.prefs.VoicePrefs

/**
 * 选择语音引擎：配了 TTS 端点用 OpenAI 兼容接口；否则在“用系统语音代替”打开时用系统 TTS；都没有就是没有可用引擎。
 */
interface TtsEngineProvider {
    suspend fun available(): Boolean
    suspend fun current(): TtsEngine?
}

class DefaultTtsEngineProvider @Inject constructor(
    private val router: ModelRouter,
    private val prefs: VoicePrefs,
    private val http: VoiceHttp,
    private val system: SystemTtsEngine,
) : TtsEngineProvider {

    override suspend fun available(): Boolean = endpoint() != null || prefs.systemFallback.first()

    override suspend fun current(): TtsEngine? {
        val endpoint = endpoint()
        return when {
            endpoint != null -> OpenAiTtsEngine(http.client, endpoint)
            prefs.systemFallback.first() -> system
            else -> null
        }
    }

    private suspend fun endpoint(): EndpointConfig? = try {
        router.endpoint(ModelRole.TTS)?.takeIf { it.baseUrl.isNotBlank() && it.model.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
