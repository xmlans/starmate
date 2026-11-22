package org.xmyun.starmate.core.llm.provider

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import org.xmyun.starmate.core.model.EmbeddingClient
import org.xmyun.starmate.core.model.EndpointConfig
import org.xmyun.starmate.core.model.LlmClient
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ModelRouter
import org.xmyun.starmate.core.model.ProviderConfig
import org.xmyun.starmate.core.model.ProviderStore
import org.xmyun.starmate.core.model.RoleAssignment
import org.xmyun.starmate.core.model.SecretStore

/** 按角色分配构造客户端。每次调用都读最新配置，不缓存客户端。 */
@Singleton
class DefaultModelRouter @Inject constructor(
    private val store: ProviderStore,
    private val secretStore: SecretStore,
    private val clientFactory: LlmClientFactory,
) : ModelRouter {

    override suspend fun chat(role: ModelRole): LlmClient? {
        require(role in CHAT_ROLES) { "chat() 只接受 CHAT、BACKGROUND、VISION，收到 $role" }
        val (assignment, provider) = resolveChat(role) ?: return null
        return clientFactory.chat(
            provider = provider,
            apiKey = keyOf(provider),
            model = assignment.model,
            supportsTools = assignment.supportsTools,
            supportsImages = assignment.supportsImages,
        )
    }

    override suspend fun embedding(): EmbeddingClient? {
        val (assignment, provider) = resolve(ModelRole.EMBEDDING) ?: return null
        return clientFactory.embedding(provider, keyOf(provider), assignment.model)
    }

    override suspend fun endpoint(role: ModelRole): EndpointConfig? {
        require(role == ModelRole.TTS || role == ModelRole.STT) { "endpoint() 只接受 TTS、STT，收到 $role" }
        val (assignment, provider) = resolve(role) ?: return null
        return EndpointConfig(provider.baseUrl, keyOf(provider), assignment.model, assignment.extras)
    }

    override suspend fun markToolsUnsupported(role: ModelRole) {
        require(role in CHAT_ROLES) { "markToolsUnsupported() 只接受 CHAT、BACKGROUND、VISION，收到 $role" }
        val (assignment, _) = resolveChat(role) ?: return
        // 回落到 CHAT 时改的是 CHAT 的分配，因为那才是实际在用的模型
        if (assignment.supportsTools) store.assign(assignment.copy(supportsTools = false))
    }

    /** BACKGROUND、VISION 没配置（或指向已删的服务商）时回落到 CHAT。 */
    private suspend fun resolveChat(role: ModelRole): Pair<RoleAssignment, ProviderConfig>? =
        resolve(role) ?: if (role != ModelRole.CHAT) resolve(ModelRole.CHAT) else null

    private suspend fun resolve(role: ModelRole): Pair<RoleAssignment, ProviderConfig>? {
        val assignment = store.assignments.first()[role] ?: return null
        val provider = store.providers.first().firstOrNull { it.id == assignment.providerId } ?: return null
        return assignment to provider
    }

    /** 本地服务可能不需要 key，没有就用空串，客户端不发鉴权头。 */
    private suspend fun keyOf(provider: ProviderConfig): String =
        secretStore.get(ProviderKeys.secretName(provider.id)).orEmpty()

    private companion object {
        val CHAT_ROLES = setOf(ModelRole.CHAT, ModelRole.BACKGROUND, ModelRole.VISION)
    }
}
