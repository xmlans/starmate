package org.xmyun.starmate.feature.voice.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ModelRouter
import org.xmyun.starmate.feature.voice.cache.VoiceCache
import org.xmyun.starmate.feature.voice.cache.VoiceMaintenance
import org.xmyun.starmate.feature.voice.play.VoicePlayer
import org.xmyun.starmate.feature.voice.prefs.VoiceFrequency
import org.xmyun.starmate.feature.voice.prefs.VoicePrefs
import org.xmyun.starmate.feature.voice.tts.TtsEngineProvider
import org.xmyun.starmate.feature.voice.tts.VoiceSynthesizer

data class VoiceSettingsUiState(
    val frequency: VoiceFrequency = VoicePrefs.DEFAULT_FREQUENCY,
    val dailyLimit: Int = VoicePrefs.DEFAULT_DAILY_LIMIT,
    val usedToday: Int = 0,
    val systemFallback: Boolean = false,
    /** 配置的 TTS 模型名；null 表示没配。 */
    val ttsModel: String? = null,
    val sttModel: String? = null,
    val cacheBytes: Long = 0,
    val sampleLoading: Boolean = false,
    val cleaning: Boolean = false,
)

sealed interface VoiceSettingsMessage {
    data object SampleUnavailable : VoiceSettingsMessage
    data object SampleFailed : VoiceSettingsMessage
    data class Cleaned(val count: Int) : VoiceSettingsMessage
}

@HiltViewModel
class VoiceSettingsViewModel @Inject constructor(
    private val prefs: VoicePrefs,
    private val router: ModelRouter,
    private val cache: VoiceCache,
    private val maintenance: VoiceMaintenance,
    private val synthesizer: VoiceSynthesizer,
    private val engines: TtsEngineProvider,
    private val player: VoicePlayer,
    private val clock: Clock,
) : ViewModel() {

    private data class Extra(
        val usedToday: Int = 0,
        val ttsModel: String? = null,
        val sttModel: String? = null,
        val cacheBytes: Long = 0,
        val sampleLoading: Boolean = false,
        val cleaning: Boolean = false,
    )

    private val extra = MutableStateFlow(Extra())
    private val _messages = Channel<VoiceSettingsMessage>(Channel.BUFFERED)
    val messages: Flow<VoiceSettingsMessage> = _messages.receiveAsFlow()

    val state: StateFlow<VoiceSettingsUiState> = combine(
        prefs.frequency,
        prefs.dailyLimit,
        prefs.systemFallback,
        extra,
    ) { frequency, limit, fallback, e ->
        VoiceSettingsUiState(
            frequency = frequency,
            dailyLimit = limit,
            usedToday = e.usedToday,
            systemFallback = fallback,
            ttsModel = e.ttsModel,
            sttModel = e.sttModel,
            cacheBytes = e.cacheBytes,
            sampleLoading = e.sampleLoading,
            cleaning = e.cleaning,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceSettingsUiState())

    /** 进入页面和回到页面时调用（模型配置可能刚改过）。 */
    fun refresh() {
        viewModelScope.launch {
            val tts = safeEndpointModel(ModelRole.TTS)
            val stt = safeEndpointModel(ModelRole.STT)
            val bytes = withContext(Dispatchers.IO) { cache.sizeBytes() }
            val used = prefs.usedToday(LocalDate.now(clock))
            extra.update { it.copy(ttsModel = tts, sttModel = stt, cacheBytes = bytes, usedToday = used) }
        }
    }

    fun setFrequency(value: VoiceFrequency) {
        viewModelScope.launch { prefs.setFrequency(value) }
    }

    fun setDailyLimit(value: Int) {
        viewModelScope.launch { prefs.setDailyLimit(value) }
    }

    fun setSystemFallback(value: Boolean) {
        viewModelScope.launch { prefs.setSystemFallback(value) }
    }

    fun playSample(text: String) {
        if (extra.value.sampleLoading) return
        viewModelScope.launch {
            if (!engines.available()) {
                _messages.trySend(VoiceSettingsMessage.SampleUnavailable)
                return@launch
            }
            extra.update { it.copy(sampleLoading = true) }
            val file = try {
                synthesizer.synthesizeSample(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            extra.update { it.copy(sampleLoading = false) }
            if (file == null) _messages.trySend(VoiceSettingsMessage.SampleFailed) else player.toggle(SAMPLE_ID, file)
        }
    }

    fun cleanOld() {
        if (extra.value.cleaning) return
        viewModelScope.launch {
            extra.update { it.copy(cleaning = true) }
            val count = try {
                maintenance.cleanOlderThan()
            } finally {
                extra.update { it.copy(cleaning = false) }
            }
            refresh()
            _messages.trySend(VoiceSettingsMessage.Cleaned(count))
        }
    }

    override fun onCleared() {
        if (player.state.value.voiceId == SAMPLE_ID) player.stop()
    }

    private suspend fun safeEndpointModel(role: ModelRole): String? = try {
        router.endpoint(role)?.model?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val SAMPLE_ID = "voice-settings-sample"
    }
}
