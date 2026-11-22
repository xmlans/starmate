package org.xmyun.starmate.feature.mcp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 服务器 tools/list 里的一个工具（原名）。 */
@Serializable
data class RemoteTool(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject = JsonObject(emptyMap()),
)

data class CachedTools(val tools: List<RemoteTool>, val fetchedAt: Instant)

/** mcp_prefs：每个预设的开关和 tools/list 缓存。key 不在这里，在 SecretStore。 */
class McpPrefs(private val store: DataStore<Preferences>) {

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(RemoteTool.serializer())

    fun enabled(preset: McpPreset): Flow<Boolean> =
        store.data.map { it[enabledKey(preset)] ?: false }.distinctUntilChanged()

    suspend fun isEnabled(preset: McpPreset): Boolean = enabled(preset).first()

    suspend fun setEnabled(preset: McpPreset, enabled: Boolean) {
        store.edit { it[enabledKey(preset)] = enabled }
    }

    suspend fun cachedTools(preset: McpPreset): CachedTools? {
        val prefs = store.data.first()
        val raw = prefs[toolsKey(preset)] ?: return null
        val at = prefs[fetchedKey(preset)] ?: return null
        val tools = try {
            json.decodeFromString(listSerializer, raw)
        } catch (e: Exception) {
            return null
        }
        return CachedTools(tools, Instant.ofEpochMilli(at))
    }

    suspend fun saveTools(preset: McpPreset, tools: List<RemoteTool>, at: Instant) {
        store.edit {
            it[toolsKey(preset)] = json.encodeToString(listSerializer, tools)
            it[fetchedKey(preset)] = at.toEpochMilli()
        }
    }

    suspend fun clearTools(preset: McpPreset) {
        store.edit {
            it.remove(toolsKey(preset))
            it.remove(fetchedKey(preset))
        }
    }

    companion object {
        const val FILE_NAME = "mcp_prefs"
        private fun enabledKey(p: McpPreset) = booleanPreferencesKey("${p.id}_enabled")
        private fun toolsKey(p: McpPreset) = stringPreferencesKey("${p.id}_tools")
        private fun fetchedKey(p: McpPreset) = longPreferencesKey("${p.id}_fetched_at")
    }
}
