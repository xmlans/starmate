package org.xmyun.starmate.feature.tools

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.model.AmbientSource
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.tools.alarm.AlarmTools
import org.xmyun.starmate.feature.tools.calendar.CalendarAmbient
import org.xmyun.starmate.feature.tools.calendar.CalendarEvent
import org.xmyun.starmate.feature.tools.calendar.CalendarTools
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.common.ToolGroup
import org.xmyun.starmate.feature.tools.common.ToolsPrefs
import org.xmyun.starmate.feature.tools.contacts.ContactsTools
import org.xmyun.starmate.feature.tools.db.ToolsDatabase
import org.xmyun.starmate.feature.tools.health.HealthAmbient
import org.xmyun.starmate.feature.tools.health.HealthReader
import org.xmyun.starmate.feature.tools.health.HealthTools
import org.xmyun.starmate.feature.tools.health.SleepSession
import org.xmyun.starmate.feature.tools.health.StepFallback
import org.xmyun.starmate.feature.tools.location.GeoFix
import org.xmyun.starmate.feature.tools.location.LocationCache
import org.xmyun.starmate.feature.tools.location.LocationRepository
import org.xmyun.starmate.feature.tools.location.LocationTools
import org.xmyun.starmate.feature.tools.location.WeatherAmbient
import org.xmyun.starmate.feature.tools.location.WeatherClient
import org.xmyun.starmate.feature.tools.music.MusicAmbient
import org.xmyun.starmate.feature.tools.music.MusicTools
import org.xmyun.starmate.feature.tools.music.NowPlaying
import org.xmyun.starmate.feature.tools.music.NowPlayingRepository

/** 未启用、未授权或用途不是 CHAT 时，工具和环境信息都不存在，也不碰任何系统接口。 */
@RunWith(RobolectricTestRunner::class)
class ToolsPrivacyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var prefs: ToolsPrefs
    private lateinit var db: ToolsDatabase
    private val permissions = FakePermissions()
    private val clock = clockAt("2026-11-10T09:00")
    private var httpCalls = 0

    private lateinit var providers: Map<ToolGroup, ToolProvider>
    private lateinit var ambients: Map<ToolGroup, AmbientSource>
    private val calendar = FakeCalendarGateway()
    private val location = FakeLocationGateway(fix = GeoFix(31.2304, 121.4737, clockAt("2026-11-10T09:00").now, true))
    private val health = FakeHealthGateway(steps = 3200)
    private lateinit var music: NowPlayingRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        prefs = newPrefs(scope, tmp.root)
        db = newDatabase()
        val gate = GroupGate(prefs, permissions)
        val text = FakeToolText()
        val app = FakeAppState(true)
        val weather = WeatherClient(
            HttpClient(MockEngine { httpCalls++; respondError(HttpStatusCode.InternalServerError) }),
            clock,
        )
        val repo = LocationRepository(location, LocationCache(prefs), prefs, app)
        val reader = HealthReader(health, StepFallback(db.stepSampleDao(), clock), clock)
        music = NowPlayingRepository(db.trackDao(), clock)
        calendar.events = listOf(CalendarEvent("面试", clock.now.plusSeconds(3600), clock.now.plusSeconds(7200), false, null))
        health.sessions = listOf(SleepSession(clock.now.minusSeconds(10 * 3600), clock.now.minusSeconds(3 * 3600)))
        providers = mapOf(
            ToolGroup.CALENDAR to CalendarTools(gate, calendar, text, clock),
            ToolGroup.ALARM to AlarmTools(gate, FakeAlarmGateway(), app, text, clock),
            ToolGroup.CONTACTS to ContactsTools(gate, FakeContactsGateway(), app, text),
            ToolGroup.LOCATION to LocationTools(gate, repo, weather, text, clock),
            ToolGroup.HEALTH to HealthTools(gate, reader, text, clock),
            ToolGroup.MUSIC to MusicTools(gate, music, text, clock),
        )
        ambients = mapOf(
            ToolGroup.CALENDAR to CalendarAmbient(gate, calendar, text, clock),
            ToolGroup.LOCATION to WeatherAmbient(gate, repo, weather, text),
            ToolGroup.HEALTH to HealthAmbient(gate, reader, text, clock),
            ToolGroup.MUSIC to MusicAmbient(gate, music, text),
        )
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    @Test
    fun nothingWhenDisabled() = runTest {
        permissions.granted += ToolGroup.entries
        music.setListenerConnected(true)
        music.update(NowPlaying("晴天", "周杰伦", "音乐", playing = true))
        providers.values.forEach { assertTrue(it.tools(TurnPurpose.CHAT).isEmpty()) }
        ambients.values.forEach { assertNull(it.snapshot()) }
        assertEquals(0, calendar.queried)
        assertEquals(0, location.fixCalls)
        assertEquals(0, httpCalls)
    }

    @Test
    fun nothingWhenEnabledButNotGranted() = runTest {
        ToolGroup.entries.forEach { prefs.enableWithAmbient(it) }
        music.update(NowPlaying("晴天", "周杰伦", "音乐", playing = true))
        providers.filterKeys { it != ToolGroup.ALARM }.values.forEach { assertTrue(it.tools(TurnPurpose.CHAT).isEmpty()) }
        ambients.values.forEach { assertNull(it.snapshot()) }
        assertEquals(0, calendar.queried)
        assertEquals(0, location.fixCalls)
    }

    @Test
    fun onlyChatPurposeGetsTools() = runTest {
        permissions.granted += ToolGroup.entries
        ToolGroup.entries.forEach { prefs.setEnabled(it, true) }
        val names = providers.values.flatMap { it.tools(TurnPurpose.CHAT) }.map { it.name }.sorted()
        assertEquals(
            listOf(
                "alarm_next", "alarm_set", "calendar_create_event", "calendar_list_events", "contacts_dial",
                "contacts_search", "health_sleep", "health_steps", "location_current", "music_now_playing",
                "music_recent", "timer_set", "weather_get",
            ),
            names,
        )
        for (purpose in TurnPurpose.entries.filter { it != TurnPurpose.CHAT }) {
            providers.values.forEach { assertTrue(it.tools(purpose).isEmpty()) }
        }
    }

    @Test
    fun musicListenerNeverReadsNotifications() {
        // 只用通知使用权取媒体会话：不覆写任何通知回调，也不读取通知列表
        val forbidden = setOf("onNotificationPosted", "onNotificationRemoved", "onNotificationRankingUpdate", "getActiveNotifications")
        val declared = org.xmyun.starmate.feature.tools.music.MediaListenerService::class.java.declaredMethods.map { it.name }
        assertTrue(declared.toString(), declared.none { it in forbidden })
    }

    @Test
    fun onDemandTierGivesNoAmbient() = runTest {
        permissions.granted += ToolGroup.entries
        ToolGroup.entries.forEach { prefs.setEnabled(it, true) }
        ambients.values.forEach { assertNull(it.snapshot()) }
        prefs.setAmbient(ToolGroup.HEALTH, true)
        assertEquals("昨晚睡了 7小时0分，今天走了 3200 步", ambients.getValue(ToolGroup.HEALTH).snapshot())
    }
}
