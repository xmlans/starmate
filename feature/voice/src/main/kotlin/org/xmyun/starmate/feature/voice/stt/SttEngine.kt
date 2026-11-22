package org.xmyun.starmate.feature.voice.stt

enum class SttFailure { NO_SPEECH, NETWORK, SERVICE, UNAVAILABLE, RECORDER, UNKNOWN }

sealed interface SttResult {
    data class Text(val text: String) : SttResult
    data class Failure(val kind: SttFailure) : SttResult
}

/**
 * 一次语音输入。start 开始听；finish 结束并返回识别结果。
 * 引擎自己停下时（例如系统识别检测到说完了、录音到达上限）调用 start 传入的 onEnded，调用方随后调 finish 取结果。
 */
interface SttEngine {
    /** 返回 false 表示没能开始（录音器打不开、识别服务不可用）。 */
    suspend fun start(onEnded: () -> Unit): Boolean

    suspend fun finish(): SttResult

    /** 放弃本次输入，不返回结果。可重复调用。 */
    fun cancel()
}
