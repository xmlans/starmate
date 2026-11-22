package org.xmyun.starmate.feature.tools.location

/** Open-Meteo 用的 WMO 天气代码 → 中英文描述。 */
object WmoCodes {

    private val table: Map<Int, Pair<String, String>> = mapOf(
        0 to ("晴" to "clear sky"),
        1 to ("大致晴朗" to "mainly clear"),
        2 to ("多云" to "partly cloudy"),
        3 to ("阴" to "overcast"),
        45 to ("雾" to "fog"),
        48 to ("雾凇" to "depositing rime fog"),
        51 to ("小毛毛雨" to "light drizzle"),
        53 to ("毛毛雨" to "moderate drizzle"),
        55 to ("大毛毛雨" to "dense drizzle"),
        56 to ("冻毛毛雨" to "light freezing drizzle"),
        57 to ("强冻毛毛雨" to "dense freezing drizzle"),
        61 to ("小雨" to "slight rain"),
        63 to ("中雨" to "moderate rain"),
        65 to ("大雨" to "heavy rain"),
        66 to ("冻雨" to "light freezing rain"),
        67 to ("强冻雨" to "heavy freezing rain"),
        71 to ("小雪" to "slight snow"),
        73 to ("中雪" to "moderate snow"),
        75 to ("大雪" to "heavy snow"),
        77 to ("米雪" to "snow grains"),
        80 to ("小阵雨" to "slight rain showers"),
        81 to ("阵雨" to "moderate rain showers"),
        82 to ("强阵雨" to "violent rain showers"),
        85 to ("小阵雪" to "slight snow showers"),
        86 to ("大阵雪" to "heavy snow showers"),
        95 to ("雷阵雨" to "thunderstorm"),
        96 to ("雷阵雨伴小冰雹" to "thunderstorm with slight hail"),
        99 to ("雷阵雨伴大冰雹" to "thunderstorm with heavy hail"),
    )

    fun describe(code: Int?, chinese: Boolean): String {
        val entry = code?.let { table[it] }
        return when {
            entry != null -> if (chinese) entry.first else entry.second
            chinese -> "未知天气"
            else -> "unknown weather"
        }
    }
}
