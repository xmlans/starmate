package org.xmyun.starmate.feature.proactive.model

import java.time.Instant

enum class CareStatus {
    PENDING,

    /** 已发出。 */
    SENT,

    /** 已处理但没发：模型决定不发，或输出无法解析。 */
    DECLINED,

    /** 用户或模型取消。 */
    CANCELLED,

    /** 从原定时间起超过 3 天仍未发出，已放弃。 */
    EXPIRED,
}

/** 她自己约下的一次关心。 */
data class Care(
    val id: Long = 0,
    val topic: String,
    val reason: String,
    /** 约定的原定时间。放弃期限从这里算起。 */
    val scheduledFor: Instant,
    /** 下一次实际唤醒的时间（已含随机偏移或顺延）。 */
    val nextAttemptAt: Instant,
    val status: CareStatus = CareStatus.PENDING,
    /** 调用过模型的次数。 */
    val attempts: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class LogSource { CARE, CHECK_IN }

enum class LogOutcome { SENT, BLOCKED, DECLINED, FAILED, GAVE_UP }

/** 发送记录：发出、被拦、模型决定不发、失败、放弃都记一条，设置页据此说明“为什么这次没发”。 */
data class SendLogEntry(
    val id: Long = 0,
    val at: Instant,
    val source: LogSource,
    val careId: Long? = null,
    val topic: String? = null,
    val outcome: LogOutcome,
    /** BLOCKED 时是 DenyReason 或 [LogReasons] 的名字；FAILED 时是 LlmErrorKind 或 [LogReasons.DELIVER_ERROR]。 */
    val reason: String? = null,
    /** 发出的文字（一行预览）。只在 SENT 时有。 */
    val text: String? = null,
)

/** 闸门之外的记录原因。 */
object LogReasons {
    /** 随机问候：距最近一条聊天消息不够久。 */
    const val NOT_IDLE = "NOT_IDLE"

    /** 随机问候：还没有任何聊天记录。 */
    const val NO_HISTORY = "NO_HISTORY"

    /** 生成期间用户开始聊天了。 */
    const val CHAT_STARTED = "CHAT_STARTED"

    const val MODEL_DECLINED = "MODEL_DECLINED"
    const val UNPARSEABLE = "UNPARSEABLE"
    const val DELIVER_ERROR = "DELIVER_ERROR"
    const val EXPIRED = "EXPIRED"
}
