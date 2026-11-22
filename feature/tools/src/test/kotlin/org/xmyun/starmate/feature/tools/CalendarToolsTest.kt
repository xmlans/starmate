package org.xmyun.starmate.feature.tools

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
import org.robolectric.annotation.Config
import org.xmyun.starmate.core.model.ToolRisk
import org.xmyun.starmate.feature.tools.calendar.CalendarEvent
import org.xmyun.starmate.feature.tools.calendar.CalendarTools
import org.xmyun.starmate.feature.tools.common.AndroidToolText
import org.xmyun.starmate.feature.tools.common.GroupGate

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh")
class CalendarToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private val clock = clockAt("2026-11-10T09:00") // 周二
    private val gateway = FakeCalendarGateway()
    private lateinit var tools: CalendarTools

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        tools = CalendarTools(GroupGate(newPrefs(scope, tmp.root), FakePermissions()), gateway, AndroidToolText(appContext()), clock)
    }

    @After
    fun tearDown() = scope.cancel()

    private fun at(local: String) = LocalDateTime.parse(local).atZone(SHANGHAI).toInstant()

    @Test
    fun rangeLongerThan31DaysIsRejected() = runTest {
        val r = tools.listEvents.execute(args("from" to "2026-11-01T00:00", "to" to "2026-12-03T00:00"), CHAT)
        assertTrue(r.isError)
        assertTrue(r.content.contains("31"))
        assertEquals(0, gateway.queried)
    }

    @Test
    fun exactly31DaysIsAllowed() = runTest {
        val r = tools.listEvents.execute(args("from" to "2026-11-01", "to" to "2026-12-01T00:00"), CHAT)
        assertFalse(r.isError)
    }

    @Test
    fun resultsAreCutTo20() = runTest {
        gateway.events = (0 until 30).map { i ->
            CalendarEvent("事件$i", at("2026-11-11T08:00").plusSeconds(i * 3600L), at("2026-11-11T08:30").plusSeconds(i * 3600L), false, null)
        }
        val r = tools.listEvents.execute(args("from" to "2026-11-11", "to" to "2026-11-13"), CHAT)
        assertFalse(r.isError)
        assertEquals(21, gateway.lastLimit)
        assertEquals(20, r.content.lines().count { it.startsWith("- ") })
        assertTrue(r.content.contains("只列出前 20 条"))
    }

    @Test
    fun listShowsLocalTimeWithWeekdayAndPlace() = runTest {
        gateway.events = listOf(CalendarEvent("面试", at("2026-11-13T15:00"), at("2026-11-13T16:00"), false, "公司"))
        val r = tools.listEvents.execute(args("from" to "2026-11-13", "to" to "2026-11-13"), CHAT)
        assertTrue(r.content, r.content.contains("- 11月13日 周五 15:00–16:00 面试（地点：公司）"))
    }

    @Test
    fun badTimeIsRejected() = runTest {
        val r = tools.listEvents.execute(args("from" to "下周", "to" to "2026-11-13"), CHAT)
        assertTrue(r.isError)
    }

    @Test
    fun createWithEndBeforeStartIsRejected() = runTest {
        val r = tools.createEvent.execute(
            args("title" to "面试", "start" to "2026-11-13T15:00", "end" to "2026-11-13T14:00"),
            CHAT,
        )
        assertTrue(r.isError)
        assertTrue(gateway.inserted.isEmpty())
    }

    @Test
    fun createWritesEvent() = runTest {
        val r = tools.createEvent.execute(
            args("title" to "面试", "start" to "2026-11-13T15:00", "end" to "2026-11-13T16:00", "location" to "公司"),
            CHAT,
        )
        assertFalse(r.content, r.isError)
        assertEquals(at("2026-11-13T15:00"), gateway.inserted.single().start)
        assertEquals("公司", gateway.inserted.single().location)
    }

    @Test
    fun describeCallReadsNaturally() {
        assertEquals(ToolRisk.WRITE, tools.createEvent.risk)
        assertEquals(
            "添加日程：周五 15:00 面试",
            tools.createEvent.describeCall(args("title" to "面试", "start" to "2026-11-13T15:00", "end" to "2026-11-13T16:00")),
        )
        assertEquals(
            "添加日程：明天 09:30 牙医",
            tools.createEvent.describeCall(args("title" to "牙医", "start" to "2026-11-11T09:30", "end" to "2026-11-11T10:00")),
        )
    }

    @Test
    @Config(qualifiers = "en")
    fun describeCallInEnglish() {
        val english = CalendarTools(GroupGate(newPrefs(scope, tmp.root), FakePermissions()), gateway, AndroidToolText(appContext()), clock)
        assertEquals(
            "Add event: Fri 15:00 Interview",
            english.createEvent.describeCall(args("title" to "Interview", "start" to "2026-11-13T15:00", "end" to "2026-11-13T16:00")),
        )
    }
}
