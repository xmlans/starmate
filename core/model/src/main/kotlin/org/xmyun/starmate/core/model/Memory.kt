package org.xmyun.starmate.core.model

import java.time.Instant

object MemoryCategory {
    const val ABOUT_USER = "about_user"   // 关于你
    const val BETWEEN_US = "between_us"   // 我们之间
}

interface MemoryWriter {
    /** importance 取 1–5。 */
    suspend fun add(text: String, category: String, importance: Int)

    /** 用户举报了她的一条回复：从记忆的原话里抹掉这段回复，之后不会再被回想起来。 */
    suspend fun forgetReply(message: ChatMessage) = Unit
}

data class DaySummary(
    val turnCount: Int,
    val gist: String,
    val newMemories: List<String>,
)

interface DaySummarySource {
    /** refresh = true 时先整理这段时间里未处理的对话再返回；false 时只读已有结果，不调用模型。 */
    suspend fun daySummary(from: Instant, to: Instant, refresh: Boolean): DaySummary?
}
