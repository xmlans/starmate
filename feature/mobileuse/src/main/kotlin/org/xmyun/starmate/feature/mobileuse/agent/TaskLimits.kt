package org.xmyun.starmate.feature.mobileuse.agent

import java.time.Duration

/**
 * 单次任务的上限。步数和时长只能调低不能调高；token 上限可在设置里调。
 * 一“步”是一次模型调用（包括解析失败的那次），这样模型调用次数一定有上限。
 */
data class TaskLimits(
    val maxSteps: Int = DEFAULT_STEPS,
    val maxDuration: Duration = DEFAULT_DURATION,
    val maxTokens: Int = DEFAULT_TOKENS,
    val maxParseFailures: Int = 3,
    /** 动作执行后等界面稳定的最长时间。 */
    val settleTimeoutMs: Long = 3_000,
    /** ask_user 和确认悬浮窗无人应答时按“否”处理的时间。 */
    val confirmTimeoutMs: Long = 60_000,
) {
    fun sanitized(): TaskLimits = copy(
        maxSteps = maxSteps.coerceIn(1, DEFAULT_STEPS),
        maxDuration = maxDuration.coerceIn(Duration.ofMinutes(1), DEFAULT_DURATION),
        maxTokens = maxTokens.coerceIn(MIN_TOKENS, MAX_TOKENS),
    )

    private fun Duration.coerceIn(min: Duration, max: Duration): Duration =
        if (this < min) min else if (this > max) max else this

    companion object {
        const val DEFAULT_STEPS = 25
        val DEFAULT_DURATION: Duration = Duration.ofMinutes(5)
        const val DEFAULT_TOKENS = 150_000
        const val MIN_TOKENS = 20_000
        const val MAX_TOKENS = 500_000
    }
}
