package org.xmyun.starmate.feature.tools.location

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

data class CurrentWeather(
    val code: Int?,
    val temperature: Double?,
    val apparent: Double?,
    val humidity: Int?,
    val windKmh: Double?,
)

data class DailyForecast(
    val date: LocalDate,
    val code: Int?,
    val max: Double?,
    val min: Double?,
    val precipitationChance: Int?,
)

data class Forecast(val current: CurrentWeather, val daily: List<DailyForecast>)

data class Place(val name: String, val latitude: Double, val longitude: Double, val admin: String?, val country: String?)

sealed interface WeatherResult {
    data class Ok(val forecast: Forecast) : WeatherResult
    data class Failed(val reason: Reason) : WeatherResult
}

sealed interface GeocodeResult {
    data class Ok(val place: Place) : GeocodeResult
    data object NotFound : GeocodeResult
    data class Failed(val reason: Reason) : GeocodeResult
}

enum class Reason { HTTP, NETWORK, BAD_DATA }

/**
 * Open-Meteo（免 key）。只请求两个地址：预报和地理编码。结果缓存 30 分钟。
 * HttpClient 由调用方传入，生产环境用 [createHttpClient]，不装日志插件。
 */
class WeatherClient(
    private val http: HttpClient,
    private val clock: Clock,
) {
    private val lock = Mutex()
    private val forecasts = HashMap<String, Pair<Instant, Forecast>>()
    private val places = HashMap<String, Pair<Instant, Place?>>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun forecast(latitude: Double, longitude: Double): WeatherResult {
        // 天气用不着精确位置：坐标只保留两位小数（约 1 公里）再发给第三方，缓存也按这个粒度
        val lat = COORDINATE_FORMAT.format(Locale.ROOT, latitude)
        val lon = COORDINATE_FORMAT.format(Locale.ROOT, longitude)
        val key = "$lat,$lon"
        lock.withLock { forecasts[key]?.takeIf { fresh(it.first) }?.let { return WeatherResult.Ok(it.second) } }
        val body = when (val r = fetch(FORECAST_URL) {
            parameter("latitude", lat)
            parameter("longitude", lon)
            parameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m")
            parameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            parameter("timezone", "auto")
            parameter("forecast_days", FORECAST_DAYS)
        }) {
            is Fetch.Ok -> r.body
            is Fetch.Failed -> return WeatherResult.Failed(r.reason)
        }
        val forecast = parseForecast(body) ?: return WeatherResult.Failed(Reason.BAD_DATA)
        lock.withLock { forecasts[key] = clock.instant() to forecast }
        return WeatherResult.Ok(forecast)
    }

    suspend fun geocode(name: String, chinese: Boolean): GeocodeResult {
        val key = (if (chinese) "zh:" else "en:") + name.trim().lowercase(Locale.ROOT)
        lock.withLock {
            places[key]?.takeIf { fresh(it.first) }?.let { cached ->
                return cached.second?.let { GeocodeResult.Ok(it) } ?: GeocodeResult.NotFound
            }
        }
        val body = when (val r = fetch(GEOCODING_URL) {
            parameter("name", name.trim())
            parameter("count", 1)
            parameter("language", if (chinese) "zh" else "en")
            parameter("format", "json")
        }) {
            is Fetch.Ok -> r.body
            is Fetch.Failed -> return GeocodeResult.Failed(r.reason)
        }
        val root = parse(body) ?: return GeocodeResult.Failed(Reason.BAD_DATA)
        val first = (root["results"] as? JsonArray)?.firstOrNull() as? JsonObject
        val place = first?.let {
            val lat = it.num("latitude") ?: return@let null
            val lon = it.num("longitude") ?: return@let null
            Place(it.str("name") ?: name.trim(), lat, lon, it.str("admin1"), it.str("country"))
        }
        lock.withLock { places[key] = clock.instant() to place }
        return place?.let { GeocodeResult.Ok(it) } ?: GeocodeResult.NotFound
    }

    private fun fresh(at: Instant) = Duration.between(at, clock.instant()) < CACHE_TTL

    private sealed interface Fetch {
        data class Ok(val body: String) : Fetch
        data class Failed(val reason: Reason) : Fetch
    }

    private suspend fun fetch(url: String, block: io.ktor.client.request.HttpRequestBuilder.() -> Unit): Fetch =
        try {
            val response = http.get(url, block)
            if (response.status.isSuccess()) Fetch.Ok(response.bodyAsText()) else Fetch.Failed(Reason.HTTP)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Fetch.Failed(Reason.NETWORK)
        }

    private fun parse(body: String): JsonObject? = try {
        json.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        null
    }

    private fun parseForecast(body: String): Forecast? {
        val root = parse(body) ?: return null
        val current = root["current"] as? JsonObject ?: return null
        val daily = root["daily"] as? JsonObject
        val days = mutableListOf<DailyForecast>()
        if (daily != null) {
            val dates = (daily["time"] as? JsonArray).orEmpty()
            for ((i, d) in dates.withIndex()) {
                val date = (d as? JsonPrimitive)?.contentOrNull?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: continue
                days += DailyForecast(
                    date = date,
                    code = daily.numAt("weather_code", i)?.toInt(),
                    max = daily.numAt("temperature_2m_max", i),
                    min = daily.numAt("temperature_2m_min", i),
                    precipitationChance = daily.numAt("precipitation_probability_max", i)?.toInt(),
                )
            }
        }
        return Forecast(
            current = CurrentWeather(
                code = current.num("weather_code")?.toInt(),
                temperature = current.num("temperature_2m"),
                apparent = current.num("apparent_temperature"),
                humidity = current.num("relative_humidity_2m")?.toInt(),
                windKmh = current.num("wind_speed_10m"),
            ),
            daily = days,
        )
    }

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.numAt(key: String, i: Int): Double? =
        ((this[key] as? JsonArray)?.getOrNull(i) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.intOrNull?.toDouble() }

    companion object {
        private const val COORDINATE_FORMAT = "%.2f"
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
        const val FORECAST_DAYS = 3
        val CACHE_TTL: Duration = Duration.ofMinutes(30)

        fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 8_000
            }
        }
    }
}
