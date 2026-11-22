package org.xmyun.starmate.feature.tools.health

import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject
import org.xmyun.starmate.feature.tools.db.StepSampleDao
import org.xmyun.starmate.feature.tools.db.StepSampleEntity

/**
 * Health Connect 没有步数时的兜底：StepBaselineWorker 每小时记录一次计步传感器的累计值。
 * 某天的步数 = 当天相邻采样的增量之和，加上跨零点那两段按时间比例分到这一天的部分。
 * 累计值变小说明手机重启过，计步器从 0 重新累计，这时新值本身就是重启后走的步数，从它重新起算。
 */
class StepFallback @Inject constructor(
    private val dao: StepSampleDao,
    private val clock: Clock,
) {
    suspend fun record(cumulative: Long) {
        val now = clock.instant()
        dao.insert(StepSampleEntity(atMillis = now.toEpochMilli(), value = cumulative))
        dao.deleteBefore(now.minus(KEEP).toEpochMilli())
    }

    /** 没有任何采样时返回 null；只有一个采样点时为 0。 */
    suspend fun stepsOn(date: LocalDate): Long? {
        val from = date.atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val samples = dao.between(from, to)
        if (samples.isEmpty()) return null
        var total = 0L
        for (i in 1 until samples.size) total += delta(samples[i - 1], samples[i])
        // 跨零点的那一段按时间比例分给两边，否则当天第一个采样之前、最后一个采样之后走的步数会全部丢掉。
        // 只看零点前后 EDGE_WINDOW 内的采样：隔得太久就不猜了。
        val window = EDGE_WINDOW.toMillis()
        dao.lastBetween(from - window, from)?.let { before ->
            total += share(before, samples.first(), boundary = from, afterBoundary = true)
        }
        dao.firstBetween(to, to + window)?.let { after ->
            total += share(samples.last(), after, boundary = to, afterBoundary = false)
        }
        return total
    }

    /** 累计值变小说明重启过，新值本身就是重启后走的步数。 */
    private fun delta(a: StepSampleEntity, b: StepSampleEntity): Long =
        if (b.value >= a.value) b.value - a.value else b.value

    /** a、b 分处 boundary 两侧时，这一段增量里落在 boundary 之后（或之前）的那部分。 */
    private fun share(a: StepSampleEntity, b: StepSampleEntity, boundary: Long, afterBoundary: Boolean): Long {
        val span = b.atMillis - a.atMillis
        if (span <= 0) return 0
        val part = if (afterBoundary) b.atMillis - boundary else boundary - a.atMillis
        return delta(a, b) * part.coerceIn(0, span) / span
    }

    companion object {
        private val KEEP: Duration = Duration.ofDays(30)
        val EDGE_WINDOW: Duration = Duration.ofHours(12)
    }
}
