package org.xmyun.starmate.feature.tools.location

import java.time.Clock
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import org.xmyun.starmate.core.model.Tool
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.model.ToolResult
import org.xmyun.starmate.core.model.ToolRisk
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.common.JsonArgs.string
import org.xmyun.starmate.feature.tools.common.Schema
import org.xmyun.starmate.feature.tools.common.TimeFmt
import org.xmyun.starmate.feature.tools.common.ToolGroup
import org.xmyun.starmate.feature.tools.common.ToolText
import org.xmyun.starmate.feature.tools.common.error
import org.xmyun.starmate.feature.tools.common.pick

class LocationTools @Inject constructor(
    private val gate: GroupGate,
    private val repository: LocationRepository,
    private val weather: WeatherClient,
    private val text: ToolText,
    private val clock: Clock,
) : ToolProvider {

    val locationCurrent: Tool = LocationCurrent()
    val weatherGet: Tool = WeatherGet()

    override suspend fun tools(purpose: TurnPurpose): List<Tool> =
        if (purpose == TurnPurpose.CHAT && gate.usable(ToolGroup.LOCATION)) listOf(locationCurrent, weatherGet) else emptyList()

    private fun noLocation() = text.error(
        "还不知道用户在哪里：没有定位权限或暂时取不到位置，用户也没有在设置里填写城市。可以直接问用户在哪个城市",
        "The user's location is unknown: no location permission or no fix available, and no city set in settings. You can ask which city they are in",
    )

    /** 位置的新旧程度。 */
    internal fun freshness(located: Located): String {
        val f = TimeFmt(clock, text.chinese)
        return when (located.source) {
            Located.Source.FRESH -> text.pick("刚刚获取的位置", "location just obtained")
            Located.Source.CACHE -> {
                val at = located.at!!
                text.pick(
                    "这是 ${f.ago(at)}（${f.dateTime(at)}）记录的位置，用户现在可能已经不在这里",
                    "recorded ${f.ago(at)} (${f.dateTime(at)}); the user may have moved since",
                )
            }
            Located.Source.MANUAL -> text.pick("用户在设置里填写的城市，不是实时定位", "city the user entered in settings, not a live location")
        }
    }

    private inner class LocationCurrent : Tool {
        override val name = "location_current"
        override val description =
            "Get the user's current location from the phone. Use it when where the user is matters, e.g. for " +
                "local suggestions or when they ask where they are. Returns coordinates (3 decimals), how fresh the " +
                "location is, and the city name when available."
        override val parametersSchema = Schema.obj()
        override val risk = ToolRisk.READ

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val located = repository.locate() ?: return noLocation()
            val coords = "%.3f, %.3f".format(Locale.ROOT, located.latitude, located.longitude)
            val city = located.city?.let { text.pick("，城市：$it", ", city: $it") }.orEmpty()
            return ToolResult(text.pick("坐标 $coords$city（${freshness(located)}）", "Coordinates $coords$city (${freshness(located)})"))
        }
    }

    private inner class WeatherGet : Tool {
        override val name = "weather_get"
        override val description =
            "Get the current weather and a 3-day forecast. Use it when the user talks about weather, going out, " +
                "clothes, or travel. Leave `city` empty for the user's current location, or give a city name to " +
                "look up another place. Returns current conditions and daily highs/lows with rain chance."
        override val parametersSchema = Schema.obj(
            "city" to Schema.string("Optional city name, e.g. 上海 or Tokyo; omit for the user's own location"),
        )
        override val risk = ToolRisk.READ

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val cityArg = args.string("city")?.take(80)
            val placeName: String?
            val lat: Double
            val lon: Double
            var note = ""
            if (cityArg != null) {
                when (val g = weather.geocode(cityArg, text.chinese)) {
                    is GeocodeResult.Ok -> {
                        placeName = g.place.name
                        lat = g.place.latitude
                        lon = g.place.longitude
                    }
                    GeocodeResult.NotFound -> return text.error("找不到叫“$cityArg”的地方", "No place named \"$cityArg\" was found")
                    is GeocodeResult.Failed -> return failure(g.reason)
                }
            } else {
                val located = repository.locate() ?: return noLocation()
                placeName = located.city
                lat = located.latitude
                lon = located.longitude
                if (located.source == Located.Source.CACHE) note = "\n" + freshness(located)
            }
            return when (val r = weather.forecast(lat, lon)) {
                is WeatherResult.Ok -> ToolResult(WeatherFormat.full(r.forecast, placeName, text.chinese, TimeFmt(clock, text.chinese)) + note)
                is WeatherResult.Failed -> failure(r.reason)
            }
        }
    }

    private fun failure(reason: Reason): ToolResult = when (reason) {
        Reason.NETWORK -> text.error("天气服务连不上，可能没有网络", "The weather service could not be reached; the phone may be offline")
        Reason.HTTP -> text.error("天气服务暂时出错了", "The weather service returned an error")
        Reason.BAD_DATA -> text.error("天气服务返回的数据看不懂", "The weather service returned unexpected data")
    }
}

/** 天气的文字写法。 */
object WeatherFormat {

    private fun deg(v: Double?) = v?.let { "${it.roundToInt()}°C" } ?: "?"

    fun short(forecast: Forecast, place: String?, chinese: Boolean): String {
        val c = forecast.current
        val desc = WmoCodes.describe(c.code, chinese)
        return listOfNotNull(place, desc, deg(c.temperature)).joinToString(" ")
    }

    fun full(forecast: Forecast, place: String?, chinese: Boolean, f: TimeFmt): String {
        val c = forecast.current
        val desc = WmoCodes.describe(c.code, chinese)
        val head = buildString {
            if (place != null) append(place).append(' ')
            if (chinese) {
                append("现在 ").append(desc).append(' ').append(deg(c.temperature))
                c.apparent?.let { append("（体感 ").append(deg(it)).append('）') }
                c.humidity?.let { append("，湿度 ").append(it).append('%') }
                c.windKmh?.let { append("，风速 ").append(it.roundToInt()).append(" km/h") }
            } else {
                append("now ").append(desc).append(", ").append(deg(c.temperature))
                c.apparent?.let { append(" (feels like ").append(deg(it)).append(')') }
                c.humidity?.let { append(", humidity ").append(it).append('%') }
                c.windKmh?.let { append(", wind ").append(it.roundToInt()).append(" km/h") }
            }
        }
        val days = forecast.daily.take(WeatherClient.FORECAST_DAYS).map { d ->
            val rain = d.precipitationChance?.let { if (chinese) "，降水概率 $it%" else ", rain $it%" }.orEmpty()
            val range = "${d.min?.roundToInt() ?: "?"}~${deg(d.max)}"
            "${f.date(d.date)}：${WmoCodes.describe(d.code, chinese)} $range$rain".let {
                if (chinese) it else "${f.date(d.date)}: ${WmoCodes.describe(d.code, false)} $range$rain"
            }
        }
        return (listOf(head) + days).joinToString("\n")
    }
}
