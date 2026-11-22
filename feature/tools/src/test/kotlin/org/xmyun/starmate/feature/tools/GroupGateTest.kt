package org.xmyun.starmate.feature.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.feature.tools.calendar.CalendarAmbient
import org.xmyun.starmate.feature.tools.calendar.CalendarEvent
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.common.GroupStatus
import org.xmyun.starmate.feature.tools.common.ManualCity
import org.xmyun.starmate.feature.tools.common.ToolGroup
import org.xmyun.starmate.feature.tools.common.ToolsPrefs

@RunWith(RobolectricTestRunner::class)
class GroupGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var prefs: ToolsPrefs
    private val permissions = FakePermissions()
    private lateinit var gate: GroupGate

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        prefs = newPrefs(scope, tmp.root)
        gate = GroupGate(prefs, permissions)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun everyGroupIsOffByDefault() = runTest {
        ToolGroup.entries.forEach {
            assertFalse(prefs.current(it).enabled)
            assertFalse(prefs.current(it).ambient)
            assertEquals(GroupStatus.OFF, gate.status(it))
        }
    }

    @Test
    fun notEnabledIsNotUsableEvenWithPermission() = runTest {
        permissions.granted += ToolGroup.CALENDAR
        assertFalse(gate.usable(ToolGroup.CALENDAR))
        assertEquals(GroupStatus.OFF, gate.status(ToolGroup.CALENDAR))
    }

    @Test
    fun enabledWithoutPermissionIsNotUsable() = runTest {
        prefs.setEnabled(ToolGroup.CALENDAR, true)
        assertFalse(gate.usable(ToolGroup.CALENDAR))
        assertEquals(GroupStatus.MISSING_PERMISSION, gate.status(ToolGroup.CALENDAR))
    }

    @Test
    fun enabledWithPermissionIsUsable() = runTest {
        prefs.setEnabled(ToolGroup.CALENDAR, true)
        permissions.granted += ToolGroup.CALENDAR
        assertTrue(gate.usable(ToolGroup.CALENDAR))
        assertEquals(GroupStatus.ON_DEMAND, gate.status(ToolGroup.CALENDAR))
        prefs.setAmbient(ToolGroup.CALENDAR, true)
        assertEquals(GroupStatus.AMBIENT, gate.status(ToolGroup.CALENDAR))
    }

    @Test
    fun ambientFalseGivesNullSnapshot() = runTest {
        val clock = clockAt("2026-11-10T09:00")
        val gateway = FakeCalendarGateway(
            listOf(CalendarEvent("面试", clock.now.plusSeconds(3600), clock.now.plusSeconds(7200), false, null)),
        )
        val ambient = CalendarAmbient(gate, gateway, FakeToolText(), clock)
        prefs.setEnabled(ToolGroup.CALENDAR, true)
        permissions.granted += ToolGroup.CALENDAR

        assertNull(ambient.snapshot())
        assertEquals(0, gateway.queried)

        prefs.setAmbient(ToolGroup.CALENDAR, true)
        assertEquals("今天还有：10:00 面试", ambient.snapshot())
    }

    @Test
    fun ambientNeedsPermissionToo() = runTest {
        val clock = clockAt("2026-11-10T09:00")
        val gateway = FakeCalendarGateway(listOf(CalendarEvent("面试", clock.now.plusSeconds(3600), clock.now.plusSeconds(7200), false, null)))
        val ambient = CalendarAmbient(gate, gateway, FakeToolText(), clock)
        prefs.enableWithAmbient(ToolGroup.CALENDAR)
        assertNull(ambient.snapshot())
        assertEquals(0, gateway.queried)
    }

    @Test
    fun alarmAndContactsHaveNoAmbientTier() = runTest {
        prefs.enableWithAmbient(ToolGroup.ALARM)
        permissions.granted += ToolGroup.ALARM
        assertFalse(gate.ambientOn(ToolGroup.ALARM))
        assertEquals(GroupStatus.ON_DEMAND, gate.status(ToolGroup.ALARM))
    }

    @Test
    fun manualCityCountsAsGrantedForLocation() = runTest {
        prefs.setEnabled(ToolGroup.LOCATION, true)
        assertFalse(gate.usable(ToolGroup.LOCATION))
        prefs.setManualCity(ManualCity("上海", 31.23, 121.47))
        assertTrue(gate.usable(ToolGroup.LOCATION))
        assertNotNull(prefs.current(ToolGroup.LOCATION))
    }

    @Test
    fun enableWithAmbientSetsBoth() = runTest {
        prefs.enableWithAmbient(ToolGroup.HEALTH)
        val s = prefs.current(ToolGroup.HEALTH)
        assertTrue(s.enabled)
        assertTrue(s.ambient)
    }
}
