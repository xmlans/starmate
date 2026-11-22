package org.xmyun.starmate.feature.proactive.tools

import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import org.xmyun.starmate.feature.proactive.model.Care
import org.xmyun.starmate.feature.proactive.model.CareStatus
import org.xmyun.starmate.feature.proactive.platform.RandomSource
import org.xmyun.starmate.feature.proactive.schedule.CareScheduler
import org.xmyun.starmate.feature.proactive.schedule.Jitter
import org.xmyun.starmate.feature.proactive.store.CareStore

sealed interface ScheduleResult {
    data class Scheduled(val care: Care) : ScheduleResult
    data class Rejected(val reason: RejectReason) : ScheduleResult
}

enum class RejectReason { BLANK_TOPIC, IN_PAST, TOO_FAR, TOO_MANY }

/** 约定的增删查。工具和设置页共用。 */
@Singleton
class CareService @Inject constructor(
    private val cares: CareStore,
    private val scheduler: CareScheduler,
    private val random: RandomSource,
    private val clock: Clock,
) {
    suspend fun schedule(at: Instant, topic: String, reason: String): ScheduleResult {
        val now = clock.instant()
        val cleanTopic = topic.trim().take(MAX_TOPIC)
        if (cleanTopic.isEmpty()) return ScheduleResult.Rejected(RejectReason.BLANK_TOPIC)
        if (!at.isAfter(now)) return ScheduleResult.Rejected(RejectReason.IN_PAST)
        if (at.isAfter(now.plus(MAX_AHEAD))) return ScheduleResult.Rejected(RejectReason.TOO_FAR)
        if (cares.countPending() >= MAX_PENDING) return ScheduleResult.Rejected(RejectReason.TOO_MANY)
        val next = Jitter.apply(at, now, random)
        val care = Care(
            topic = cleanTopic,
            reason = reason.trim().take(MAX_REASON),
            scheduledFor = at,
            nextAttemptAt = next,
            createdAt = now,
            updatedAt = now,
        )
        val id = cares.insert(care)
        scheduler.schedule(id, next)
        return ScheduleResult.Scheduled(care.copy(id = id))
    }

    /** 只有待发的约定能取消。 */
    suspend fun cancel(id: Long): Boolean {
        val care = cares.byId(id) ?: return false
        if (care.status != CareStatus.PENDING) return false
        cares.update(care.copy(status = CareStatus.CANCELLED, updatedAt = clock.instant()))
        scheduler.cancel(id)
        return true
    }

    suspend fun pending(): List<Care> = cares.pending()

    companion object {
        val MAX_AHEAD: Duration = Duration.ofDays(90)
        const val MAX_PENDING = 20
        private const val MAX_TOPIC = 200
        private const val MAX_REASON = 500
    }
}
