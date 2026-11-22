package org.xmyun.starmate.feature.proactive.schedule

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.core.model.QuietHours
import org.xmyun.starmate.feature.proactive.FixedRandom
import org.xmyun.starmate.feature.proactive.TEST_ZONE
import org.xmyun.starmate.feature.proactive.gate.DenyReason
import org.xmyun.starmate.feature.proactive.local

class CareDeferralTest {

    private val quiet = QuietHours.DEFAULT // 23:00–08:00

    @Test
    fun quietHoursBlock_defersToAfterQuietEnd() {
        val scheduled = local("2026-11-12T23:10")
        val now = local("2026-11-12T23:20")
        val result = CareDeferral.decide(DenyReason.QUIET_HOURS, scheduled, now, TEST_ZONE, quiet, FixedRandom(300))
        val quietEnd = local("2026-11-13T08:00")
        assertEquals(Deferral.RetryAt(quietEnd.plusSeconds(300)), result)
    }

    @Test
    fun quietHoursAfterMidnight_defersToSameMorning() {
        val now = local("2026-11-13T02:00")
        val result = CareDeferral.decide(DenyReason.QUIET_HOURS, now, now, TEST_ZONE, quiet, FixedRandom(Long.MAX_VALUE))
        val retry = (result as Deferral.RetryAt).at
        val quietEnd = local("2026-11-13T08:00")
        assertFalse(retry.isBefore(quietEnd))
        assertTrue(retry <= quietEnd.plus(Duration.ofMinutes(15)))
    }

    @Test
    fun otherReasons_retryInOneHour() {
        val scheduled = local("2026-11-12T18:00")
        val now = local("2026-11-12T18:05")
        for (reason in listOf(DenyReason.TOO_SOON, DenyReason.CHATTING, DenyReason.BUDGET, DenyReason.DAILY_CAP, null)) {
            val result = CareDeferral.decide(reason, scheduled, now, TEST_ZONE, quiet, FixedRandom(0))
            assertEquals("reason $reason", Deferral.RetryAt(now.plus(Duration.ofHours(1))), result)
        }
    }

    @Test
    fun moreThanThreeDaysAfterSchedule_givesUp() {
        val scheduled = local("2026-11-10T18:00")
        val now = scheduled.plus(Duration.ofDays(3))
        assertEquals(Deferral.GiveUp, CareDeferral.decide(DenyReason.TOO_SOON, scheduled, now, TEST_ZONE, quiet, FixedRandom(0)))
    }

    @Test
    fun retryBeyondDeadline_givesUp() {
        val scheduled = local("2026-11-10T18:00")
        val now = scheduled.plus(Duration.ofDays(3)).minus(Duration.ofMinutes(30))
        assertEquals(Deferral.GiveUp, CareDeferral.decide(DenyReason.TOO_SOON, scheduled, now, TEST_ZONE, quiet, FixedRandom(0)))
    }

    @Test
    fun justInsideDeadline_stillRetries() {
        val scheduled = local("2026-11-10T18:00")
        val now = scheduled.plus(Duration.ofDays(2))
        assertTrue(CareDeferral.decide(DenyReason.TOO_SOON, scheduled, now, TEST_ZONE, quiet, FixedRandom(0)) is Deferral.RetryAt)
    }
}
