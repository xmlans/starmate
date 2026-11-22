package org.xmyun.starmate.feature.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
import org.robolectric.annotation.Config
import org.xmyun.starmate.feature.tools.alarm.AlarmTools
import org.xmyun.starmate.feature.tools.common.AndroidToolText
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.common.WriteOutcome

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh")
class AlarmToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private val clock = clockAt("2026-11-10T09:00")
    private val gateway = FakeAlarmGateway()
    private val app = FakeAppState(foreground = true)
    private lateinit var tools: AlarmTools

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        tools = AlarmTools(GroupGate(newPrefs(scope, tmp.root), FakePermissions()), gateway, app, AndroidToolText(appContext()), clock)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun backgroundReturnsError() = runTest {
        app.flow.value = false
        val alarm = tools.alarmSet.execute(args("hour" to 7, "minute" to 30), CHAT)
        val timer = tools.timerSet.execute(args("seconds" to 300), CHAT)
        assertTrue(alarm.isError)
        assertTrue(alarm.content.contains("前台"))
        assertTrue(timer.isError)
        assertTrue(gateway.alarms.isEmpty())
        assertTrue(gateway.timers.isEmpty())
    }

    @Test
    fun hour24IsRejected() = runTest {
        val r = tools.alarmSet.execute(args("hour" to 24, "minute" to 0), CHAT)
        assertTrue(r.isError)
        assertTrue(gateway.alarms.isEmpty())
    }

    @Test
    fun invalidDaysAreRejected() = runTest {
        val r = tools.alarmSet.execute(args("hour" to 7, "minute" to 0, "days" to JsonArray(listOf(JsonPrimitive(8)))), CHAT)
        assertTrue(r.isError)
    }

    @Test
    fun noClockAppReturnsError() = runTest {
        gateway.outcome = WriteOutcome.NO_APP
        val r = tools.alarmSet.execute(args("hour" to 7, "minute" to 30), CHAT)
        assertTrue(r.isError)
        assertTrue(r.content.contains("时钟应用"))
        val t = tools.timerSet.execute(args("seconds" to 60), CHAT)
        assertTrue(t.isError)
    }

    @Test
    fun setsAlarmWithRepeatDays() = runTest {
        val r = tools.alarmSet.execute(
            args("hour" to "7", "minute" to 30, "label" to "起床", "days" to JsonArray((1..5).map { JsonPrimitive(it) })),
            CHAT,
        )
        assertFalse(r.content, r.isError)
        assertEquals(listOf(7, 30, "起床", listOf(1, 2, 3, 4, 5)), gateway.alarms.single())
    }

    @Test
    fun timerRangeChecked() = runTest {
        assertTrue(tools.timerSet.execute(args("seconds" to 0), CHAT).isError)
        assertTrue(tools.timerSet.execute(args("seconds" to 86_401), CHAT).isError)
    }

    @Test
    fun nextAlarm() = runTest {
        assertEquals("手机上没有设置闹钟", tools.alarmNext.execute(args(), CHAT).content)
        gateway.next = clockAt("2026-11-11T07:30").now
        assertEquals("下一个闹钟：11月11日 周三 07:30", tools.alarmNext.execute(args(), CHAT).content)
    }

    @Test
    fun describeCalls() {
        assertEquals(
            "设闹钟：07:30 起床，工作日重复",
            tools.alarmSet.describeCall(args("hour" to 7, "minute" to 30, "label" to "起床", "days" to JsonArray((1..5).map { JsonPrimitive(it) }))),
        )
        assertEquals("设闹钟：06:05", tools.alarmSet.describeCall(args("hour" to 6, "minute" to 5)))
        assertEquals("开始计时：10分钟（泡面）", tools.timerSet.describeCall(args("seconds" to 600, "label" to "泡面")))
    }
}
