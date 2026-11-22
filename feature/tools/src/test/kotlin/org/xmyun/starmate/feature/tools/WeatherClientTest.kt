package org.xmyun.starmate.feature.tools

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.feature.tools.location.GeocodeResult
import org.xmyun.starmate.feature.tools.location.Reason
import org.xmyun.starmate.feature.tools.location.WeatherClient
import org.xmyun.starmate.feature.tools.location.WeatherFormat
import org.xmyun.starmate.feature.tools.location.WeatherResult
import org.xmyun.starmate.feature.tools.common.TimeFmt

class WeatherClientTest {

    private val clock = clockAt("2026-11-10T09:00")
    private val requests = mutableListOf<String>()

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String = FORECAST) = WeatherClient(
        HttpClient(
            MockEngine { request ->
                requests += request.url.toString()
                if (status == HttpStatusCode.OK) {
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    respondError(status)
                }
            },
        ),
        clock,
    )

    @Test
    fun parsesCurrentAndThreeDays() = runTest {
        val r = client().forecast(31.2304, 121.4737)
        val forecast = (r as WeatherResult.Ok).forecast
        assertEquals(61, forecast.current.code)
        assertEquals(12.3, forecast.current.temperature!!, 0.001)
        assertEquals(3, forecast.daily.size)
        assertEquals(LocalDate.parse("2026-11-11"), forecast.daily[1].date)
        assertEquals(80, forecast.daily[0].precipitationChance)

        val url = requests.single()
        assertTrue(url, url.startsWith(WeatherClient.FORECAST_URL + "?"))
        assertTrue(url.contains("forecast_days=3"))
        assertTrue(url.contains("timezone=auto"))

        val text = WeatherFormat.full(forecast, "上海", true, TimeFmt(clock, true))
        assertEquals(
            "上海 现在 小雨 12°C（体感 10°C），湿度 85%，风速 13 km/h\n" +
                "11月10日 周二：小雨 9~14°C，降水概率 80%\n" +
                "11月11日 周三：多云 8~15°C，降水概率 10%\n" +
                "11月12日 周四：晴 7~16°C，降水概率 0%",
            text,
        )
        assertEquals("上海 小雨 12°C", WeatherFormat.short(forecast, "上海", true))
    }

    @Test
    fun sendsCoordinatesRoundedToAboutOneKilometre() = runTest {
        client().forecast(31.230416, 121.473701)

        val url = requests.single()
        assertTrue(url, url.contains("latitude=31.23&"))
        assertTrue(url, url.contains("longitude=121.47&"))
        // 精确到门牌的那几位小数不该出现在发给第三方的请求里
        assertFalse(url, url.contains("31.2304"))
        assertFalse(url, url.contains("121.4737"))
    }

    @Test
    fun secondCallWithin30MinutesUsesCache() = runTest {
        val c = client()
        c.forecast(31.2304, 121.4737)
        clock.now = clock.now.plus(Duration.ofMinutes(29))
        c.forecast(31.2311, 121.4740) // 四舍五入到两位小数后是同一处
        assertEquals(1, requests.size)
        clock.now = clock.now.plus(Duration.ofMinutes(2))
        c.forecast(31.2304, 121.4737)
        assertEquals(2, requests.size)
    }

    @Test
    fun httpErrorIsFailure() = runTest {
        val r = client(HttpStatusCode.InternalServerError).forecast(31.23, 121.47)
        assertEquals(WeatherResult.Failed(Reason.HTTP), r)
    }

    @Test
    fun badBodyIsFailure() = runTest {
        val r = client(body = "not json").forecast(31.23, 121.47)
        assertEquals(WeatherResult.Failed(Reason.BAD_DATA), r)
    }

    @Test
    fun geocodeFirstResult() = runTest {
        val c = client(body = """{"results":[{"name":"上海","latitude":31.22222,"longitude":121.45806,"country":"中国","admin1":"上海市"}]}""")
        val r = c.geocode("上海", chinese = true) as GeocodeResult.Ok
        assertEquals("上海", r.place.name)
        assertEquals(31.22222, r.place.latitude, 1e-6)
        assertTrue(requests.single().startsWith(WeatherClient.GEOCODING_URL + "?"))
        assertTrue(requests.single().contains("language=zh"))
    }

    @Test
    fun geocodeNoResults() = runTest {
        assertEquals(GeocodeResult.NotFound, client(body = """{"generationtime_ms":0.5}""").geocode("Atlantis", false))
    }

    companion object {
        val FORECAST = """
            {
              "latitude": 31.25, "longitude": 121.5, "timezone": "Asia/Shanghai",
              "current": {"time": "2026-11-10T09:00", "interval": 900, "temperature_2m": 12.3,
                "apparent_temperature": 10.1, "relative_humidity_2m": 85, "weather_code": 61, "wind_speed_10m": 12.7},
              "daily": {
                "time": ["2026-11-10", "2026-11-11", "2026-11-12"],
                "weather_code": [61, 2, 0],
                "temperature_2m_max": [14.2, 15.0, 16.4],
                "temperature_2m_min": [9.1, 8.0, 7.2],
                "precipitation_probability_max": [80, 10, 0]
              }
            }
        """.trimIndent()
    }
}
