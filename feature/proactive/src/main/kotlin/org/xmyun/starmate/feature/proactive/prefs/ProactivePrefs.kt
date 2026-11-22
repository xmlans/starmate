package org.xmyun.starmate.feature.proactive.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 本模块自己的偏好（文件 proactive_prefs）。粘人程度和免打扰时段在共享的 CompanionPrefs 里。
 * 由 DI 传入 DataStore，测试里用临时文件构造。
 */
class ProactivePrefs(private val store: DataStore<Preferences>) {

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    /** 主动消息总开关，默认打开（花费另有每日后台预算兜底）。 */
    val enabled: Flow<Boolean> = data.map { it[ENABLED] ?: DEFAULT_ENABLED }

    /** 保活前台服务，默认关闭。 */
    val keepAlive: Flow<Boolean> = data.map { it[KEEP_ALIVE] ?: false }

    suspend fun setEnabled(value: Boolean) {
        store.edit { it[ENABLED] = value }
    }

    suspend fun setKeepAlive(value: Boolean) {
        store.edit { it[KEEP_ALIVE] = value }
    }

    /** 导出用。保活是设备相关的设置，不导出。 */
    suspend fun exportJson(): String =
        buildJsonObject { put(KEY_ENABLED, enabled.first()) }.toString()

    /** 内容不合法时忽略，不抛异常。 */
    suspend fun importJson(text: String) {
        val obj = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return
        val value = (obj[KEY_ENABLED] as? JsonPrimitive)?.booleanOrNull ?: return
        setEnabled(value)
    }

    companion object {
        const val FILE_NAME = "proactive_prefs"
        const val DEFAULT_ENABLED = true
        private const val KEY_ENABLED = "enabled"
        private val ENABLED = booleanPreferencesKey(KEY_ENABLED)
        private val KEEP_ALIVE = booleanPreferencesKey("keep_alive")
    }
}
