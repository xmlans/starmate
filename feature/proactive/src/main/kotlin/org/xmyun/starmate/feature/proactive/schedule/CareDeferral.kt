package org.xmyun.starmate.feature.proactive.schedule

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.xmyun.starmate.core.model.QuietHours
import org.xmyun.starmate.feature.proactive.gate.DenyReason
import org.xmyun.starmate.feature.proactive.platform.RandomSource

sealed interface Deferral {
    data class RetryAt(val at: Instant) : Deferral
    data object GiveUp : Deferral
}

/**
 * 约定没能发出时的顺延规则（纯函数）：
 * - 因免打扰被拦：顺延到免打扰结束之后，再往后加随机偏移；
 * - 其他原因（含模型调用失败，reason 为 null）：1 小时后重试；
 * - 从原定时间起超过 3 天还没发出，或顺延后会超过这个期限：放弃。
 */
object CareDeferral {
    val RETRY_DELAY: Duration = Duration.ofHours(1)
    val GIVE_UP_AFTER: Duration = Duration.ofDays(3)

    fun deadline(scheduledFor: Instant): Instant = scheduledFor.plus(GIVE_UP_AFTER)

    fun isExpired(scheduledFor: Instant, now: Instant): Boolean = !now.isBefore(deadline(scheduledFor))

    fun decide(
        reason: DenyReason?,
        scheduledFor: Instant,
        now: Instant,
        zone: ZoneId,
        quietHours: QuietHours,
        random: RandomSource,
    ): Deferral {
        if (isExpired(scheduledFor, now)) return Deferral.GiveUp
        val next = if (reason == DenyReason.QUIET_HOURS) {
            Jitter.after(quietHoursEnd(now, zone, quietHours), random)
        } else {
            now.plus(RETRY_DELAY)
        }
        return if (next.isAfter(deadline(scheduledFor))) Deferral.GiveUp else Deferral.RetryAt(next)
    }

    /** now 之后最近一次免打扰结束的时刻。 */
    fun quietHoursEnd(now: Instant, zone: ZoneId, quietHours: QuietHours): Instant {
        val endTime = LocalTime.of(quietHours.endMinute / 60, quietHours.endMinute % 60)
        val today = now.atZone(zone).toLocalDate()
        val candidate = today.atTime(endTime).atZone(zone).toInstant()
        return if (candidate.isAfter(now)) candidate else today.plusDays(1).atTime(endTime).atZone(zone).toInstant()
    }
}
