package org.xmyun.starmate.core.model

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

data class UsageEntry(
    val purpose: TurnPurpose,
    val model: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val ttsChars: Int = 0,
    val at: Instant,
)

data class DailyUsage(
    val date: LocalDate,
    val purpose: TurnPurpose,
    val inputTokens: Long,
    val outputTokens: Long,
    val ttsChars: Long,
)

interface UsageLedger {
    suspend fun record(entry: UsageEntry)

    /** 今天该用途已用的 token（输入 + 输出），按本地自然日。 */
    suspend fun usedToday(purpose: TurnPurpose): Int

    /** 非后台用途恒为 true。后台用途在今天用量低于预算时为 true。 */
    suspend fun allow(purpose: TurnPurpose): Boolean

    fun observeDaily(days: Int): Flow<List<DailyUsage>>
}
