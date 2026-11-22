package org.xmyun.starmate.feature.voice.stt

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ModelRouter
import org.xmyun.starmate.feature.voice.VoiceHttp

/** 配了 STT 端点用 Whisper 兼容接口，否则用系统语音识别。 */
interface SttEngineFactory {
    suspend fun create(): SttEngine
}

class DefaultSttEngineFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: ModelRouter,
    private val http: VoiceHttp,
    private val recorder: Recorder,
) : SttEngineFactory {
    override suspend fun create(): SttEngine {
        val endpoint = try {
            router.endpoint(ModelRole.STT)?.takeIf { it.baseUrl.isNotBlank() && it.model.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return if (endpoint != null) {
            WhisperSttEngine(http.client, endpoint, recorder, File(context.cacheDir, "voice_input"))
        } else {
            SystemSttEngine(context)
        }
    }
}

enum class MicState { IDLE, RECORDING, TRANSCRIBING }

sealed interface MicEvent {
    data class Recognized(val text: String) : MicEvent
    data class Failed(val kind: SttFailure) : MicEvent
}

/**
 * 输入栏麦克风的状态机：按一下开始，再按一下结束并识别；最长 60 秒自动结束。
 * 结果通过 [events] 发给界面，由界面填进输入框。
 */
@Singleton
class MicInputController(
    private val factory: SttEngineFactory,
    private val diag: DiagLog,
    private val scope: CoroutineScope,
    private val maxDurationMs: Long = MAX_DURATION_MS,
) {
    @Inject
    constructor(factory: SttEngineFactory, diag: DiagLog) :
        this(factory, diag, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    private val _state = MutableStateFlow(MicState.IDLE)
    val state: StateFlow<MicState> = _state.asStateFlow()

    private val _events = Channel<MicEvent>(Channel.BUFFERED)
    val events: Flow<MicEvent> = _events.receiveAsFlow()

    private var engine: SttEngine? = null
    private var session: Job? = null
    private var limitJob: Job? = null

    fun toggle() {
        when (_state.value) {
            MicState.IDLE -> start()
            MicState.RECORDING -> stop()
            MicState.TRANSCRIBING -> Unit
        }
    }

    private fun start() {
        _state.value = MicState.RECORDING
        session = scope.launch {
            val created = try {
                factory.create()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val started = created != null && try {
                created.start(onEnded = { scope.launch { stop() } })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!started) {
                created?.cancel()
                _state.value = MicState.IDLE
                _events.trySend(MicEvent.Failed(SttFailure.UNAVAILABLE))
                return@launch
            }
            engine = created
            limitJob = scope.launch {
                delay(maxDurationMs)
                stop()
            }
        }
    }

    private fun stop() {
        val current = engine ?: return
        if (_state.value != MicState.RECORDING) return
        engine = null
        limitJob?.cancel()
        limitJob = null
        _state.value = MicState.TRANSCRIBING
        scope.launch {
            val result = try {
                current.finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diag.log(TAG, "speech input failed: ${e.javaClass.simpleName}")
                SttResult.Failure(SttFailure.UNKNOWN)
            }
            _state.value = MicState.IDLE
            when (result) {
                is SttResult.Text -> _events.trySend(MicEvent.Recognized(result.text))
                is SttResult.Failure -> {
                    diag.log(TAG, "speech input failed: ${result.kind}")
                    _events.trySend(MicEvent.Failed(result.kind))
                }
            }
        }
    }

    /** 离开聊天页时调用：丢弃正在进行的录音。 */
    fun cancel() {
        session?.cancel()
        session = null
        limitJob?.cancel()
        limitJob = null
        engine?.cancel()
        engine = null
        if (_state.value == MicState.RECORDING) _state.value = MicState.IDLE
    }

    private companion object {
        const val TAG = "voice"
        const val MAX_DURATION_MS = 60_000L
    }
}
