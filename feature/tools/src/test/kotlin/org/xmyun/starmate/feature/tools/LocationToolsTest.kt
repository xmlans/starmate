package org.xmyun.starmate.feature.tools

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Duration
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
import org.xmyun.starmate.feature.tools.common.ManualCity
import org.xmyun.starmate.feature.tools.common.ToolsPrefs
import org.xmyun.starmate.feature.tools.location.GeoFix
import org.xmyun.starmate.feature.tools.location.LocationCache
import org.xmyun.starmate.feature.tools.location.LocationRepository
import org.xmyun.starmate.feature.tools.location.LocationTools
import org.xmyun.starmate.feature.tools.location.WeatherClient

@RunWith(RobolectricTestRunner::class)
class LocationToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var prefs: ToolsPrefs
    private val clock = clockAt("2026-11-10T09:00")
    private val gateway = FakeLocationGateway()
    private val app = FakeAppState(true)
    private var weatherStatus = HttpStatusCode.OK
    private lateinit var tools: LocationTools

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        prefs = newPrefs(scope, tmp.root)
        val weather = WeatherClient(
            HttpClient(
                MockEngine {
                    if (weatherStatus == HttpStatusCode.OK) {
                        respond(WeatherClientTest.FORECAST, weatherStatus, headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        respondError(weatherStatus)
                    }
                },
            ),
            clock,
        )
        tools = LocationTools(
            GroupGate(prefs, FakePermissions()),
            LocationRepository(gateway, LocationCache(prefs), prefs, app),
            weather,
            FakeToolText(),
            clock,
        )
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun freshFixInForeground() = runTest {
        gateway.fix = GeoFix(31.230416, 121.473701, clock.now, true)
        gateway.city = "上海"
        val r = tools.locationCurrent.execute(args(), CHAT)
        assertEquals("坐标 31.230, 121.474，城市：上海（刚刚获取的位置）", r.content)
    }

    @Test
    fun backgroundNeverFetchesAndShowsCacheTime() = runTest {
        // 前台时拿到一次位置并写入缓存
        gateway.fix = GeoFix(31.2304, 121.4737, clock.now, false)
        gateway.city = "上海"
        tools.locationCurrent.execute(args(), CHAT)
        assertEquals(1, gateway.fixCalls)

        // 转到后台两小时后：只读缓存，不再取位置
        app.flow.value = false
        clock.now = clock.now.plus(Duration.ofHours(2))
        val r = tools.locationCurrent.execute(args(), CHAT)
        assertEquals(1, gateway.fixCalls)
        assertFalse(r.isError)
        assertTrue(r.content, r.content.contains("2 小时前（11月10日 周二 09:00）记录的位置"))
    }

    @Test
    fun noLocationAndNoCityIsExplainedError() = runTest {
        gateway.permission = false
        val r = tools.locationCurrent.execute(args(), CHAT)
        assertTrue(r.isError)
        assertTrue(r.content.contains("没有定位权限"))
        assertTrue(r.content.contains("城市"))
        assertEquals(0, gateway.fixCalls)
        assertTrue(tools.weatherGet.execute(args(), CHAT).isError)
    }

    @Test
    fun manualCityUsedWhenNoPermission() = runTest {
        gateway.permission = false
        prefs.setManualCity(ManualCity("杭州", 30.29365, 120.16142))
        val r = tools.locationCurrent.execute(args(), CHAT)
        assertFalse(r.isError)
        assertTrue(r.content.contains("杭州"))
        assertTrue(r.content.contains("设置里填写"))
        val w = tools.weatherGet.execute(args(), CHAT)
        assertTrue(w.content, w.content.startsWith("杭州 现在 小雨"))
    }

    @Test
    fun weatherHttpErrorIsErrorResult() = runTest {
        gateway.fix = GeoFix(31.23, 121.47, clock.now, true)
        weatherStatus = HttpStatusCode.ServiceUnavailable
        val r = tools.weatherGet.execute(args(), CHAT)
        assertTrue(r.isError)
        assertTrue(r.content.contains("天气服务"))
    }
}
