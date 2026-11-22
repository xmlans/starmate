package org.xmyun.starmate.feature.proactive.store

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.xmyun.starmate.feature.proactive.db.CareEntity
import org.xmyun.starmate.feature.proactive.db.ProactiveDatabaseHolder
import org.xmyun.starmate.feature.proactive.db.SendLogEntity
import org.xmyun.starmate.feature.proactive.model.Care
import org.xmyun.starmate.feature.proactive.model.CareStatus
import org.xmyun.starmate.feature.proactive.model.LogOutcome
import org.xmyun.starmate.feature.proactive.model.LogSource
import org.xmyun.starmate.feature.proactive.model.SendLogEntry

interface CareStore {
    /** 传入的 id 被忽略，返回新 id。 */
    suspend fun insert(care: Care): Long
    suspend fun update(care: Care)
    suspend fun byId(id: Long): Care?

    /** 待发的约定，按原定时间排序。 */
    suspend fun pending(): List<Care>
    fun observePending(): Flow<List<Care>>
    suspend fun countPending(): Int
}

interface SendLogStore {
    suspend fun add(entry: SendLogEntry)

    /** 最近 limit 条，新的在前。 */
    fun observeRecent(limit: Int): Flow<List<SendLogEntry>>
    suspend fun countSentSince(from: Instant): Int

    /** after 之后（不含）发出的条数；after 为 null 时统计全部。 */
    suspend fun countSentAfter(after: Instant?): Int
    suspend fun lastSentAt(): Instant?
}

@Singleton
class RoomCareStore @Inject constructor(
    private val holder: ProactiveDatabaseHolder,
) : CareStore {
    override suspend fun insert(care: Care): Long = holder.dao().insertCare(care.toEntity().copy(id = 0))

    override suspend fun update(care: Care) = holder.dao().updateCare(care.toEntity())

    override suspend fun byId(id: Long): Care? = holder.dao().care(id)?.toModel()

    override suspend fun pending(): List<Care> =
        holder.dao().caresWithStatus(CareStatus.PENDING.name).map { it.toModel() }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePending(): Flow<List<Care>> =
        holder.database.filterNotNull()
            .flatMapLatest { it.dao().observeCaresWithStatus(CareStatus.PENDING.name) }
            .map { list -> list.map { it.toModel() } }

    override suspend fun countPending(): Int = holder.dao().countCares(CareStatus.PENDING.name)
}

@Singleton
class RoomSendLogStore @Inject constructor(
    private val holder: ProactiveDatabaseHolder,
) : SendLogStore {
    override suspend fun add(entry: SendLogEntry) {
        val dao = holder.dao()
        dao.insertLog(entry.toEntity().copy(id = 0))
        dao.pruneLogs(KEEP)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeRecent(limit: Int): Flow<List<SendLogEntry>> =
        holder.database.filterNotNull()
            .flatMapLatest { it.dao().observeRecentLogs(limit) }
            .map { list -> list.map { it.toModel() } }

    override suspend fun countSentSince(from: Instant): Int =
        holder.dao().countLogsSince(LogOutcome.SENT.name, from.toEpochMilli())

    override suspend fun countSentAfter(after: Instant?): Int =
        holder.dao().countLogsAfter(LogOutcome.SENT.name, after?.toEpochMilli() ?: Long.MIN_VALUE)

    override suspend fun lastSentAt(): Instant? =
        holder.dao().lastLogAt(LogOutcome.SENT.name)?.let(Instant::ofEpochMilli)

    companion object {
        /** 只保留最近这么多条记录。 */
        const val KEEP = 200
    }
}

private fun Care.toEntity() = CareEntity(
    id = id,
    topic = topic,
    reason = reason,
    scheduledFor = scheduledFor.toEpochMilli(),
    nextAttemptAt = nextAttemptAt.toEpochMilli(),
    status = status.name,
    attempts = attempts,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
)

private fun CareEntity.toModel() = Care(
    id = id,
    topic = topic,
    reason = reason,
    scheduledFor = Instant.ofEpochMilli(scheduledFor),
    nextAttemptAt = Instant.ofEpochMilli(nextAttemptAt),
    status = CareStatus.entries.firstOrNull { it.name == status } ?: CareStatus.CANCELLED,
    attempts = attempts,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

private fun SendLogEntry.toEntity() = SendLogEntity(
    id = id,
    at = at.toEpochMilli(),
    source = source.name,
    careId = careId,
    topic = topic,
    outcome = outcome.name,
    reason = reason,
    text = text,
)

private fun SendLogEntity.toModel() = SendLogEntry(
    id = id,
    at = Instant.ofEpochMilli(at),
    source = LogSource.entries.firstOrNull { it.name == source } ?: LogSource.CHECK_IN,
    careId = careId,
    topic = topic,
    outcome = LogOutcome.entries.firstOrNull { it.name == outcome } ?: LogOutcome.FAILED,
    reason = reason,
    text = text,
)
