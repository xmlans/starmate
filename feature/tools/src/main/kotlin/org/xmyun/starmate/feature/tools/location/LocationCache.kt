package org.xmyun.starmate.feature.tools.location

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.xmyun.starmate.core.model.AppState
import org.xmyun.starmate.feature.tools.common.ToolsPrefs

data class CachedLocation(val fix: GeoFix, val city: String?)

/** 最近一次前台定位的结果（含时间戳），存在 tools_prefs 里。 */
class LocationCache @Inject constructor(private val prefs: ToolsPrefs) {

    suspend fun get(): CachedLocation? {
        val p = prefs.data.first()
        val lat = p[LAT] ?: return null
        val lon = p[LON] ?: return null
        val at = p[AT] ?: return null
        return CachedLocation(GeoFix(lat, lon, Instant.ofEpochMilli(at), p[PRECISE] ?: false), p[CITY])
    }

    suspend fun put(fix: GeoFix, city: String?) {
        prefs.edit {
            it[LAT] = fix.latitude
            it[LON] = fix.longitude
            it[AT] = fix.at.toEpochMilli()
            it[PRECISE] = fix.precise
            if (city != null) it[CITY] = city else it.remove(CITY)
        }
    }

    suspend fun clear() {
        prefs.edit { it.remove(LAT); it.remove(LON); it.remove(AT); it.remove(PRECISE); it.remove(CITY) }
    }

    private companion object {
        val LAT = doublePreferencesKey("loc_lat")
        val LON = doublePreferencesKey("loc_lon")
        val AT = longPreferencesKey("loc_at")
        val PRECISE = booleanPreferencesKey("loc_precise")
        val CITY = stringPreferencesKey("loc_city")
    }
}

/** 一个可用的位置及其来源。at 为 null 表示用户手填的城市。 */
data class Located(
    val latitude: Double,
    val longitude: Double,
    val city: String?,
    val at: Instant?,
    val source: Source,
) {
    enum class Source { FRESH, CACHE, MANUAL }
}

/**
 * 取位置：前台且有权限时现取并写入缓存；后台或现取失败时只读缓存；
 * 都没有时用手填的城市。没有定位权限时不用缓存。
 */
class LocationRepository @Inject constructor(
    private val gateway: LocationGateway,
    private val cache: LocationCache,
    private val prefs: ToolsPrefs,
    private val appState: AppState,
) {
    suspend fun locate(freshTimeoutMs: Long = FRESH_TIMEOUT_MS): Located? {
        val permitted = gateway.hasPermission()
        if (permitted && appState.isForeground.value) {
            val fix = try {
                withTimeoutOrNull(freshTimeoutMs) { gateway.currentFix() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (fix != null) {
                val previous = cache.get()
                val city = withTimeoutOrNull(CITY_TIMEOUT_MS) { gateway.cityName(fix.latitude, fix.longitude) }
                    ?: previous?.city?.takeIf { previous.fix.isNear(fix) }
                cache.put(fix, city)
                return Located(fix.latitude, fix.longitude, city, fix.at, Located.Source.FRESH)
            }
        }
        if (permitted) {
            cache.get()?.let { return Located(it.fix.latitude, it.fix.longitude, it.city, it.fix.at, Located.Source.CACHE) }
        }
        prefs.manualCity.first()?.let { return Located(it.latitude, it.longitude, it.name, null, Located.Source.MANUAL) }
        return null
    }

    private fun GeoFix.isNear(other: GeoFix): Boolean =
        Math.abs(latitude - other.latitude) < 0.05 && Math.abs(longitude - other.longitude) < 0.05

    companion object {
        const val FRESH_TIMEOUT_MS = 8_000L
        private const val CITY_TIMEOUT_MS = 3_000L
    }
}
