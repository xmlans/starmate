package org.xmyun.starmate.core.llm.provider

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import org.xmyun.starmate.core.llm.anthropic.AnthropicClient
import org.xmyun.starmate.core.llm.anthropic.AnthropicWire
import org.xmyun.starmate.core.llm.di.ProvidersDataStore
import org.xmyun.starmate.core.llm.http.DIAG_TAG
import org.xmyun.starmate.core.llm.http.HttpClientFactory
import org.xmyun.starmate.core.llm.http.LlmJson
import org.xmyun.starmate.core.llm.http.http
import org.xmyun.starmate.core.llm.http.joinUrl
import org.xmyun.starmate.core.llm.openai.OpenAiWire
import org.xmyun.starmate.core.model.ConnectionTest
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.LlmException
import org.xmyun.starmate.core.model.LlmMessage
import org.xmyun.starmate.core.model.LlmRequest
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ProviderConfig
import org.xmyun.starmate.core.model.ProviderKind
import org.xmyun.starmate.core.model.ProviderPreset
import org.xmyun.starmate.core.model.ProviderStore
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.RoleAssignment
import org.xmyun.starmate.core.model.SecretStore
import org.xmyun.starmate.core.model.SystemPrompt

/**
 * 服务商和角色分配以 JSON 存进 DataStore `providers`；key 只存 SecretStore（名字见 [ProviderKeys]）。
 */
@Singleton
class DataStoreProviderStore @Inject constructor(
    @param:ProvidersDataStore private val dataStore: DataStore<Preferences>,
    private val secretStore: SecretStore,
    private val clientFactory: LlmClientFactory,
    private val httpFactory: HttpClientFactory,
    private val diag: DiagLog,
) : ProviderStore {

    override val presets: List<ProviderPreset> = Presets.all

    private val safeData: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) {
            diag.log(DIAG_TAG, "读取服务商配置失败：${e.javaClass.simpleName}")
            emit(emptyPreferences())
        } else {
            throw e
        }
    }

    override val providers: Flow<List<ProviderConfig>> =
        safeData.map { decodeProviders(it) }.distinctUntilChanged()

    override val assignments: Flow<Map<ModelRole, RoleAssignment>> =
        safeData.map { decodeAssignments(it) }.distinctUntilChanged()

    override suspend fun saveProvider(config: ProviderConfig, apiKey: String?) {
        dataStore.edit { prefs ->
            val list = decodeProviders(prefs).filterNot { it.id == config.id } + config
            prefs[PROVIDERS_KEY] = encodeProviders(list)
        }
        when {
            apiKey == null -> Unit // 保留原来的 key
            apiKey.isBlank() -> secretStore.set(ProviderKeys.secretName(config.id), null)
            else -> secretStore.set(ProviderKeys.secretName(config.id), apiKey.trim())
        }
    }

    override suspend fun deleteProvider(id: String) {
        dataStore.edit { prefs ->
            prefs[PROVIDERS_KEY] = encodeProviders(decodeProviders(prefs).filterNot { it.id == id })
            prefs[ASSIGNMENTS_KEY] = encodeAssignments(decodeAssignments(prefs).filterValues { it.providerId != id })
        }
        secretStore.set(ProviderKeys.secretName(id), null)
    }

    override suspend fun assign(assignment: RoleAssignment) {
        dataStore.edit { prefs ->
            prefs[ASSIGNMENTS_KEY] = encodeAssignments(decodeAssignments(prefs) + (assignment.role to assignment))
        }
    }

    override suspend fun unassign(role: ModelRole) {
        dataStore.edit { prefs ->
            prefs[ASSIGNMENTS_KEY] = encodeAssignments(decodeAssignments(prefs) - role)
        }
    }

    override suspend fun listModels(providerId: String): List<String> {
        val provider = providers.first().firstOrNull { it.id == providerId } ?: return emptyList()
        val key = secretStore.get(ProviderKeys.secretName(providerId)).orEmpty()
        return try {
            val url = when (provider.kind) {
                ProviderKind.OPENAI_COMPAT -> joinUrl(provider.baseUrl, "/models")
                ProviderKind.ANTHROPIC -> joinUrl(provider.baseUrl, "/v1/models?limit=1000")
            }
            val response = httpFactory.client.get(url) {
                if (key.isNotBlank()) {
                    when (provider.kind) {
                        ProviderKind.OPENAI_COMPAT -> header(HttpHeaders.Authorization, "Bearer $key")
                        ProviderKind.ANTHROPIC -> header("x-api-key", key)
                    }
                }
                if (provider.kind == ProviderKind.ANTHROPIC) header("anthropic-version", AnthropicClient.API_VERSION)
            }
            diag.http("GET", url, response.status.value)
            if (!response.status.isSuccess()) return emptyList()
            val body = LlmJson.parseToJsonElement(response.bodyAsText()).jsonObject
            val ids = when (provider.kind) {
                ProviderKind.OPENAI_COMPAT -> OpenAiWire.modelIds(body)
                ProviderKind.ANTHROPIC -> AnthropicWire.modelIds(body)
            }
            ids.distinct().sorted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.log(DIAG_TAG, "获取模型列表失败：${e.javaClass.simpleName}")
            emptyList()
        }
    }

    override suspend fun test(providerId: String, model: String): ConnectionTest {
        val provider = providers.first().firstOrNull { it.id == providerId }
            ?: return ConnectionTest.Failed(LlmErrorKind.NOT_CONFIGURED, "服务商不存在")
        val key = secretStore.get(ProviderKeys.secretName(providerId)).orEmpty()
        val client = clientFactory.chat(provider, key, model, supportsTools = false, supportsImages = false)
        val request = LlmRequest(
            system = SystemPrompt(stable = ""),
            messages = listOf(LlmMessage(Role.USER, "hi")),
            maxOutputTokens = 8,
        )
        return try {
            client.stream(request).collect { }
            ConnectionTest.Ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            ConnectionTest.Failed(e.kind, e.message.orEmpty())
        }
    }

    @Serializable
    private data class StoredProvider(val id: String, val displayName: String, val kind: String, val baseUrl: String)

    @Serializable
    private data class StoredAssignment(
        val role: String,
        val providerId: String,
        val model: String,
        val supportsImages: Boolean = false,
        val supportsTools: Boolean = true,
        val extras: Map<String, String> = emptyMap(),
    )

    private fun decodeProviders(prefs: Preferences): List<ProviderConfig> {
        val raw = prefs[PROVIDERS_KEY] ?: return emptyList()
        return runCatching { LlmJson.decodeFromString(ListSerializer(StoredProvider.serializer()), raw) }
            .getOrElse {
                diag.log(DIAG_TAG, "服务商配置损坏，按空处理")
                emptyList()
            }
            .mapNotNull { s ->
                val kind = runCatching { ProviderKind.valueOf(s.kind) }.getOrNull() ?: return@mapNotNull null
                ProviderConfig(s.id, s.displayName, kind, s.baseUrl)
            }
    }

    private fun encodeProviders(list: List<ProviderConfig>): String =
        LlmJson.encodeToString(
            ListSerializer(StoredProvider.serializer()),
            list.map { StoredProvider(it.id, it.displayName, it.kind.name, it.baseUrl) },
        )

    private fun decodeAssignments(prefs: Preferences): Map<ModelRole, RoleAssignment> {
        val raw = prefs[ASSIGNMENTS_KEY] ?: return emptyMap()
        return runCatching { LlmJson.decodeFromString(ListSerializer(StoredAssignment.serializer()), raw) }
            .getOrElse {
                diag.log(DIAG_TAG, "角色分配损坏，按空处理")
                emptyList()
            }
            .mapNotNull { s ->
                val role = runCatching { ModelRole.valueOf(s.role) }.getOrNull() ?: return@mapNotNull null
                RoleAssignment(role, s.providerId, s.model, s.supportsImages, s.supportsTools, s.extras)
            }
            .associateBy { it.role }
    }

    private fun encodeAssignments(map: Map<ModelRole, RoleAssignment>): String =
        LlmJson.encodeToString(
            ListSerializer(StoredAssignment.serializer()),
            map.values.sortedBy { it.role.ordinal }.map {
                StoredAssignment(it.role.name, it.providerId, it.model, it.supportsImages, it.supportsTools, it.extras)
            },
        )

    private companion object {
        val PROVIDERS_KEY = stringPreferencesKey("providers_json")
        val ASSIGNMENTS_KEY = stringPreferencesKey("assignments_json")
    }
}
