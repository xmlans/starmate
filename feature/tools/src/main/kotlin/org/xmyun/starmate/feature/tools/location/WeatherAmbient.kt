package org.xmyun.starmate.feature.tools.location

import javax.inject.Inject
import kotlinx.coroutines.withTimeoutOrNull
import org.xmyun.starmate.core.model.AmbientSource
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.common.ToolGroup
import org.xmyun.starmate.feature.tools.common.ToolText

/** 一行：城市、天气、气温。引擎只等 2 秒，这里自己在 1.8 秒内收尾。 */
class WeatherAmbient @Inject constructor(
    private val gate: GroupGate,
    private val repository: LocationRepository,
    private val weather: WeatherClient,
    private val text: ToolText,
) : AmbientSource {
    override val id: String = "tools_weather"

    override suspend fun snapshot(): String? {
        if (!gate.ambientOn(ToolGroup.LOCATION)) return null
        return withTimeoutOrNull(BUDGET_MS) {
            val located = repository.locate(freshTimeoutMs = FRESH_MS) ?: return@withTimeoutOrNull null
            when (val r = weather.forecast(located.latitude, located.longitude)) {
                is WeatherResult.Ok -> WeatherFormat.short(r.forecast, located.city, text.chinese)
                is WeatherResult.Failed -> null
            }
        }
    }

    companion object {
        private const val BUDGET_MS = 1_800L
        private const val FRESH_MS = 800L
    }
}
