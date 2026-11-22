package org.xmyun.starmate.core.model

import kotlinx.coroutines.flow.Flow

enum class ProviderKind { OPENAI_COMPAT, ANTHROPIC }

data class ProviderPreset(
    val id: String,
    val displayName: String,
    val kind: ProviderKind,
    val baseUrl: String,
)

data class ProviderConfig(
    val id: String,
    val displayName: String,
    val kind: ProviderKind,
    val baseUrl: String,
)

data class RoleAssignment(
    val role: ModelRole,
    val providerId: String,
    val model: String,
    val supportsImages: Boolean = false,
    val supportsTools: Boolean = true,
    val extras: Map<String, String> = emptyMap(),
)

/** 接口地址的规则。 */
object EndpointUrls {
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1")

    /**
     * 这个接口地址能不能用。必须是 https：key 放在请求头里，明文传输时同一个网络上的人都看得到。
     * 唯一的例外是本机回环地址（localhost、127.0.0.1），给跑在这台手机上的模型服务用，数据不出手机。
     * 系统层面由 app 的 network_security_config 做同样的限制，这里是为了在保存前给出看得懂的提示。
     */
    fun isAcceptable(url: String): Boolean {
        val uri = try {
            java.net.URI(url.trim())
        } catch (e: java.net.URISyntaxException) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        return when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> host in LOOPBACK_HOSTS
            else -> false
        }
    }
}

sealed interface ConnectionTest {
    data object Ok : ConnectionTest
    data class Failed(val kind: LlmErrorKind, val message: String) : ConnectionTest
}

interface ProviderStore {
    val presets: List<ProviderPreset>
    val providers: Flow<List<ProviderConfig>>
    val assignments: Flow<Map<ModelRole, RoleAssignment>>

    /** apiKey 为 null 表示保留原来的 key。 */
    suspend fun saveProvider(config: ProviderConfig, apiKey: String?)
    suspend fun deleteProvider(id: String)
    suspend fun assign(assignment: RoleAssignment)
    suspend fun unassign(role: ModelRole)
    suspend fun listModels(providerId: String): List<String>
    suspend fun test(providerId: String, model: String): ConnectionTest
}
