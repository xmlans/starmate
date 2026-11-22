package org.xmyun.starmate.core.data.store

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.xmyun.starmate.core.data.db.ChatDatabaseHolder
import org.xmyun.starmate.core.data.db.UsageEntity
import org.xmyun.starmate.core.data.db.toEntity
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.DailyUsage
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UsageEntry
import org.xmyun.starmate.core.model.UsageLedger

/** 自然日按 [ZoneId.systemDefault] 划分，当前时间取自注入的 [Clock]。 */
@Singleton
class RoomUsageLedger @Inject constructor(
    private val holder: ChatDatabaseHolder,
    private val prefs: CompanionPrefs,
    private val clock: Clock,
) : UsageLedger {

    override suspend fun record(entry: UsageEntry) {
        holder.dao().insertUsage(entry.toEntity())
    }

    override suspend fun usedToday(purpose: TurnPurpose): Int {
        val zone = ZoneId.systemDefault()
        val today = clock.instant().atZone(zone).toLocalDate()
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sum = holder.dao().sumTokens(purpose.name, from, to)
        return sum.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    override suspend fun allow(purpose: TurnPurpose): Boolean {
        if (!purpose.isBackground) return true
        return usedToday(purpose) < prefs.dailyBudget(purpose).first()
    }

    /** 最近 days 个自然日（含今天），按日期、用途分组，日期升序。窗口起点在调用时确定。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeDaily(days: Int): Flow<List<DailyUsage>> {
        if (days <= 0) return flowOf(emptyList())
        val zone = ZoneId.systemDefault()
        val today = clock.instant().atZone(zone).toLocalDate()
        val from = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        return holder.database
            .filterNotNull()
            .flatMapLatest { it.chatDao().observeUsageSince(from) }
            .map { rows -> group(rows, zone) }
    }

    private fun group(rows: List<UsageEntity>, zone: ZoneId): List<DailyUsage> {
        data class Key(val date: LocalDate, val purpose: TurnPurpose)

        return rows
            .mapNotNull { row ->
                val purpose = TurnPurpose.entries.firstOrNull { it.name == row.purpose } ?: return@mapNotNull null
                Key(Instant.ofEpochMilli(row.at).atZone(zone).toLocalDate(), purpose) to row
            }
            .groupBy({ it.first }, { it.second })
            .map { (key, list) ->
                DailyUsage(
                    date = key.date,
                    purpose = key.purpose,
                    inputTokens = list.sumOf { it.inputTokens.toLong() },
                    outputTokens = list.sumOf { it.outputTokens.toLong() },
                    ttsChars = list.sumOf { it.ttsChars.toLong() },
                )
            }
            .sortedWith(compareBy({ it.date }, { it.purpose.ordinal }))
    }
}
