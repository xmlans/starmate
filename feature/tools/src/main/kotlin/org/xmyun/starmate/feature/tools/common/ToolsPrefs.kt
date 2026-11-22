package org.xmyun.starmate.feature.tools.common

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class GroupSettings(val enabled: Boolean = false, val ambient: Boolean = false)

/** 用户拒绝定位时在设置里手填的城市（已经过地理编码）。 */
data class ManualCity(val name: String, val latitude: Double, val longitude: Double)

/**
 * tools_prefs：每组的开关和档位、手填城市，以及 LocationCache 的数据。
 * 构造时传入 DataStore，测试里可以换成临时文件。
 */
class ToolsPrefs(private val store: DataStore<Preferences>) {

    val data: Flow<Preferences> get() = store.data

    fun settings(group: ToolGroup): Flow<GroupSettings> =
        store.data.map { it.settingsOf(group) }.distinctUntilChanged()

    val allSettings: Flow<Map<ToolGroup, GroupSettings>> =
        store.data.map { prefs -> ToolGroup.entries.associateWith { prefs.settingsOf(it) } }.distinctUntilChanged()

    suspend fun current(group: ToolGroup): GroupSettings = store.data.first().settingsOf(group)

    suspend fun setEnabled(group: ToolGroup, enabled: Boolean) {
        store.edit { it[enabledKey(group)] = enabled }
    }

    suspend fun setAmbient(group: ToolGroup, ambient: Boolean) {
        store.edit { it[ambientKey(group)] = ambient && group.hasAmbient }
    }

    /** 权限邀请页授权成功后调用：启用并设为“让她随时知道”。 */
    suspend fun enableWithAmbient(group: ToolGroup) {
        store.edit {
            it[enabledKey(group)] = true
            it[ambientKey(group)] = group.hasAmbient
        }
    }

    val manualCity: Flow<ManualCity?> = store.data.map { prefs ->
        val name = prefs[CITY_NAME] ?: return@map null
        val lat = prefs[CITY_LAT] ?: return@map null
        val lon = prefs[CITY_LON] ?: return@map null
        ManualCity(name, lat, lon)
    }.distinctUntilChanged()

    suspend fun setManualCity(city: ManualCity?) {
        store.edit {
            if (city == null) {
                it.remove(CITY_NAME); it.remove(CITY_LAT); it.remove(CITY_LON)
            } else {
                it[CITY_NAME] = city.name
                it[CITY_LAT] = city.latitude
                it[CITY_LON] = city.longitude
            }
        }
    }

    suspend fun edit(transform: (MutablePreferences) -> Unit) {
        store.edit { transform(it) }
    }

    private fun Preferences.settingsOf(group: ToolGroup) = GroupSettings(
        enabled = this[enabledKey(group)] ?: false,
        ambient = (this[ambientKey(group)] ?: false) && group.hasAmbient,
    )

    companion object {
        const val FILE_NAME = "tools_prefs"
        private val CITY_NAME = stringPreferencesKey("city_name")
        private val CITY_LAT = doublePreferencesKey("city_lat")
        private val CITY_LON = doublePreferencesKey("city_lon")

        private fun enabledKey(group: ToolGroup) = booleanPreferencesKey("${group.key}_enabled")
        private fun ambientKey(group: ToolGroup) = booleanPreferencesKey("${group.key}_ambient")
    }
}
