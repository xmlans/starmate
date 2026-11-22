package org.xmyun.starmate.feature.proactive.gate

import java.time.Duration
import java.time.Instant
import org.xmyun.starmate.core.model.Clinginess

/** 随机问候在闸门之外的额外条件：距最近一条聊天消息足够久。 */
object CheckInCondition {
    val MIN_IDLE: Duration = Duration.ofHours(6)
    val MIN_IDLE_HIGH: Duration = Duration.ofHours(4)

    fun minIdle(clinginess: Clinginess): Duration =
        if (clinginess == Clinginess.HIGH) MIN_IDLE_HIGH else MIN_IDLE

    /** 还没有任何聊天记录时不问候：没聊过就突然来消息并不自然，也不该为此花钱。 */
    fun isIdleEnough(now: Instant, lastChatMessageAt: Instant?, clinginess: Clinginess): Boolean {
        if (lastChatMessageAt == null) return false
        return Duration.between(lastChatMessageAt, now) >= minIdle(clinginess)
    }
}
