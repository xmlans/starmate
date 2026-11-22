package org.xmyun.starmate.feature.proactive.gate

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.QuietHours

enum class DenyReason { DISABLED, NO_NETWORK, QUIET_HOURS, DAILY_CAP, TOO_SOON, UNANSWERED, CHATTING, BUDGET }

sealed interface GateResult {
    data object Allow : GateResult
    data class Deny(val reason: DenyReason) : GateResult
}

data class GateInput(
    val now: Instant,
    val zone: ZoneId,
    /** 主动消息总开关。 */
    val enabled: Boolean,
    val clinginess: Clinginess,
    val networkAvailable: Boolean,
    val quietHours: QuietHours,
    /** 今天（本地自然日）已发出的主动消息数。 */
    val sentToday: Int,
    val lastProactiveAt: Instant?,
    /** 最近一条用户消息之后发出的主动消息数。 */
    val proactiveSinceLastUserMessage: Int,
    /** 最近一条聊天消息（任一方）的时间。 */
    val lastChatMessageAt: Instant?,
    /** 前台正有一轮对话在进行。 */
    val engineBusy: Boolean,
    /** UsageLedger.allow(PROACTIVE)。 */
    val budgetAllows: Boolean,
)

/** 本地闸门。纯函数；全部通过才允许调用模型。检查顺序固定，返回第一道没通过的原因。 */
object ProactiveGate {
    val MIN_GAP: Duration = Duration.ofHours(6)
    const val MAX_UNANSWERED = 2
    val CHAT_COOLDOWN: Duration = Duration.ofMinutes(10)

    fun evaluate(input: GateInput): GateResult {
        val reason = firstDenial(input) ?: return GateResult.Allow
        return GateResult.Deny(reason)
    }

    private fun firstDenial(input: GateInput): DenyReason? {
        if (!input.enabled) return DenyReason.DISABLED
        if (input.clinginess == Clinginess.OFF) return DenyReason.DISABLED
        if (!input.networkAvailable) return DenyReason.NO_NETWORK
        if (input.quietHours.contains(minuteOfDay(input.now, input.zone))) return DenyReason.QUIET_HOURS
        if (input.sentToday >= input.clinginess.maxPerDay) return DenyReason.DAILY_CAP
        // 上一条在“未来”（系统时间被调回）也视为太近
        if (input.lastProactiveAt != null && Duration.between(input.lastProactiveAt, input.now) < MIN_GAP) {
            return DenyReason.TOO_SOON
        }
        if (input.proactiveSinceLastUserMessage >= MAX_UNANSWERED) return DenyReason.UNANSWERED
        if (input.engineBusy) return DenyReason.CHATTING
        if (input.lastChatMessageAt != null && Duration.between(input.lastChatMessageAt, input.now) < CHAT_COOLDOWN) {
            return DenyReason.CHATTING
        }
        if (!input.budgetAllows) return DenyReason.BUDGET
        return null
    }

    fun minuteOfDay(now: Instant, zone: ZoneId): Int {
        val local = now.atZone(zone)
        return local.hour * 60 + local.minute
    }
}
