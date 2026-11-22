package org.xmyun.starmate.feature.proactive.gate

import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.feature.proactive.local

class CheckInConditionTest {

    private val now = local("2026-11-12T15:00")

    private fun hoursAgo(hours: Long) = now.minus(Duration.ofHours(hours))

    @Test
    fun fiveHoursSinceChat_doesNotTrigger() {
        assertFalse(CheckInCondition.isIdleEnough(now, hoursAgo(5), Clinginess.MEDIUM))
    }

    @Test
    fun sixHoursSinceChat_triggers() {
        assertTrue(CheckInCondition.isIdleEnough(now, hoursAgo(6), Clinginess.MEDIUM))
        assertTrue(CheckInCondition.isIdleEnough(now, hoursAgo(6), Clinginess.LOW))
    }

    @Test
    fun highTriggersAfterFourHours() {
        assertTrue(CheckInCondition.isIdleEnough(now, hoursAgo(4), Clinginess.HIGH))
        assertFalse(CheckInCondition.isIdleEnough(now, hoursAgo(3), Clinginess.HIGH))
        assertFalse(CheckInCondition.isIdleEnough(now, hoursAgo(4), Clinginess.MEDIUM))
    }

    @Test
    fun noChatHistory_doesNotTrigger() {
        assertFalse(CheckInCondition.isIdleEnough(now, null, Clinginess.HIGH))
    }
}
