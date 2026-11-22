package org.xmyun.starmate.feature.proactive.schedule

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.feature.proactive.FakeCheckInScheduler
import org.xmyun.starmate.feature.proactive.FakeCompanionPrefs
import org.xmyun.starmate.feature.proactive.FakeDiag
import org.xmyun.starmate.feature.proactive.FakeScheduler
import org.xmyun.starmate.feature.proactive.FixedRandom
import org.xmyun.starmate.feature.proactive.InMemoryCareStore
import org.xmyun.starmate.feature.proactive.InMemorySendLogStore
import org.xmyun.starmate.feature.proactive.MutableClock
import org.xmyun.starmate.feature.proactive.local
import org.xmyun.starmate.feature.proactive.model.Care
import org.xmyun.starmate.feature.proactive.model.CareStatus
import org.xmyun.starmate.feature.proactive.model.LogOutcome
import org.xmyun.starmate.feature.proactive.model.LogReasons
import org.xmyun.starmate.feature.proactive.newPrefs

class CareReschedulerTest {

    private val now = local("2026-11-12T15:00")
    private val clock = MutableClock(now)
    private val cares = InMemoryCareStore()
    private val logs = InMemorySendLogStore()
    private val scheduler = FakeScheduler()
    private val checkIn = FakeCheckInScheduler()
    private val companionPrefs = FakeCompanionPrefs(clinginess = Clinginess.MEDIUM)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = newPrefs()
    private val rescheduler =
        CareRescheduler(cares, logs, scheduler, checkIn, prefs, companionPrefs, FixedRandom(60), clock, FakeDiag())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun care(topic: String, scheduledFor: Instant, next: Instant = scheduledFor): Long =
        cares.insert(
            Care(topic = topic, reason = "", scheduledFor = scheduledFor, nextAttemptAt = next, createdAt = now, updatedAt = now),
        )

    /** 一条未来的、一条挂起期间错过的、一条超过 3 天的。 */
    private suspend fun seed(): Triple<Long, Long, Long> = Triple(
        care("future", now.plus(Duration.ofHours(3))),
        care("missed", now.minus(Duration.ofHours(5))),
        care("stale", now.minus(Duration.ofDays(3)).minusSeconds(1)),
    )

    @Test
    fun disabled_cancelsAlarms_keepsCaresPending_noLogs() = runBlocking {
        val (future, missed, stale) = seed()
        prefs.setEnabled(false)

        rescheduler.rescheduleAll()

        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(scheduler.cancelled.containsAll(listOf(future, missed, stale)))
        assertEquals(3, cares.countPending())
        assertTrue(logs.entries.isEmpty())
        assertEquals(false, checkIn.enabled)
    }

    @Test
    fun reenabled_registersEveryPendingCare_andGivesUpStaleOne() = runBlocking {
        val (future, missed, stale) = seed()
        prefs.setEnabled(false)
        rescheduler.rescheduleAll()

        prefs.setEnabled(true)
        rescheduler.rescheduleAll()

        assertEquals(setOf(future, missed), scheduler.scheduled.keys)
        assertEquals(now.plus(Duration.ofHours(3)), scheduler.scheduled[future])
        // 错过的在 1–5 分钟内触发（FixedRandom(60) → 1 分钟 + 60 秒）
        assertEquals(now.plus(Duration.ofMinutes(2)), scheduler.scheduled[missed])
        assertEquals(CareStatus.EXPIRED, cares.byId(stale)!!.status)
        val gaveUp = logs.entries.single()
        assertEquals(LogOutcome.GAVE_UP, gaveUp.outcome)
        assertEquals(LogReasons.EXPIRED, gaveUp.reason)
        assertEquals(stale, gaveUp.careId)
        assertEquals(true, checkIn.enabled)

        // 再对齐一次不会重复放弃
        rescheduler.rescheduleAll()
        assertEquals(1, logs.entries.size)
    }

    @Test
    fun pastDueAlarm_isWithinAFewMinutes() = runBlocking {
        val missed = care("missed", now.minus(Duration.ofHours(1)))
        for (value in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val r = CareRescheduler(cares, logs, scheduler, checkIn, prefs, companionPrefs, FixedRandom(value), clock, FakeDiag())
            r.rescheduleAll()
            val at = scheduler.scheduled[missed]!!
            assertFalse(at.isBefore(now.plus(Duration.ofMinutes(1))))
            assertFalse(at.isAfter(now.plus(Duration.ofMinutes(5))))
        }
    }

    @Test
    fun followSettings_masterSwitchOn_registersPendingCares() = runBlocking {
        val (future, missed, stale) = seed()
        prefs.setEnabled(false)
        val job = scope.launch { rescheduler.followSettings() }
        try {
            // 启动时按当前（关闭）设置对齐
            waitUntil { checkIn.enabled == false }
            assertTrue(scheduler.scheduled.isEmpty())

            prefs.setEnabled(true)
            waitUntil { scheduler.scheduled.keys == setOf(future, missed) }
            assertEquals(CareStatus.EXPIRED, cares.byId(stale)!!.status)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun followSettings_clinginessOffToLow_registersPendingCares() = runBlocking {
        val (future, missed, stale) = seed()
        companionPrefs.clinginessState.value = Clinginess.OFF
        val job = scope.launch { rescheduler.followSettings() }
        try {
            waitUntil { checkIn.enabled == false }
            assertTrue(scheduler.scheduled.isEmpty())
            assertTrue(logs.entries.isEmpty())

            companionPrefs.clinginessState.value = Clinginess.LOW
            waitUntil { scheduler.scheduled.keys == setOf(future, missed) && checkIn.enabled == true }
            assertEquals(CareStatus.EXPIRED, cares.byId(stale)!!.status)
            assertEquals(LogOutcome.GAVE_UP, logs.entries.single().outcome)

            // 改回“关”：撤掉闹钟
            companionPrefs.clinginessState.value = Clinginess.OFF
            waitUntil { scheduler.scheduled.isEmpty() && checkIn.enabled == false }
        } finally {
            job.cancel()
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }
}
