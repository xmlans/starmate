package org.xmyun.starmate.feature.tools

import java.time.Duration
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.db.ToolsDatabase
import org.xmyun.starmate.feature.tools.health.HealthAvailability
import org.xmyun.starmate.feature.tools.health.HealthData
import org.xmyun.starmate.feature.tools.health.HealthReader
import org.xmyun.starmate.feature.tools.health.HealthTools
import org.xmyun.starmate.feature.tools.health.SleepSession
import org.xmyun.starmate.feature.tools.health.StepFallback

@RunWith(RobolectricTestRunner::class)
class HealthToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var db: ToolsDatabase
    private val clock = clockAt("2026-11-10T09:00")
    private val gateway = FakeHealthGateway()
    private lateinit var fallback: StepFallback
    private lateinit var tools: HealthTools

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        db = newDatabase()
        fallback = StepFallback(db.stepSampleDao(), clock)
        tools = HealthTools(
            GroupGate(newPrefs(scope, tmp.root), FakePermissions()),
            HealthReader(gateway, fallback, clock),
            FakeToolText(),
            clock,
        )
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun at(local: String) = LocalDateTime.parse(local).atZone(SHANGHAI).toInstant()

    @Test
    fun daysAbove7AreClampedTo7() = runTest {
        gateway.sessions = (0..9).map { d ->
            val wake = at("2026-11-10T07:00").minus(Duration.ofDays(d.toLong()))
            SleepSession(wake.minus(Duration.ofHours(8)), wake)
        }
        val r = tools.sleep.execute(args("days" to 10), CHAT)
        assertFalse(r.isError)
        assertEquals(7, r.content.lines().size)
        // 查询窗口不早于 7 天前的那一晚
        assertTrue(gateway.lastSleepFrom!! >= at("2026-11-03T00:00").minus(Duration.ofHours(18)))
        assertEquals("11月10日 周二：23:00 入睡，07:00 起床，共 8小时0分", r.content.lines().last())
    }

    @Test
    fun noSleepDataText() = runTest {
        val r = tools.sleep.execute(args(), CHAT)
        assertTrue(r.isError)
        assertEquals("没有睡眠数据（昨晚没有记录）", r.content)
    }

    @Test
    fun sleepWithoutPermissionOrHealthConnect() = runTest {
        gateway.granted = setOf(HealthData.STEPS)
        assertTrue(tools.sleep.execute(args(), CHAT).content.contains("没有授权"))
        gateway.availability = HealthAvailability.UNAVAILABLE
        assertTrue(tools.sleep.execute(args(), CHAT).content.contains("没有可用的 Health Connect"))
    }

    @Test
    fun stepsFromHealthConnect() = runTest {
        gateway.steps = 4321
        val r = tools.steps.execute(args(), CHAT)
        assertEquals("11月10日 周二：4321 步", r.content)
    }

    @Test
    fun fallbackIsLabelled() = runTest {
        gateway.steps = null
        gateway.stepCounter = true
        clock.now = at("2026-11-10T01:00")
        fallback.record(5_000)
        clock.now = at("2026-11-10T09:00")
        fallback.record(7_500)
        val r = tools.steps.execute(args("date" to "2026-11-10"), CHAT)
        assertFalse(r.isError)
        assertEquals("11月10日 周二：2500 步（来自手机计步器，可能不完整）", r.content)
    }

    @Test
    fun noStepsAnywhere() = runTest {
        gateway.steps = null
        val r = tools.steps.execute(args(), CHAT)
        assertTrue(r.isError)
        assertTrue(r.content.contains("没有步数数据"))
    }

    @Test
    fun futureDateRejected() = runTest {
        assertTrue(tools.steps.execute(args("date" to "2026-11-11"), CHAT).isError)
    }
}
