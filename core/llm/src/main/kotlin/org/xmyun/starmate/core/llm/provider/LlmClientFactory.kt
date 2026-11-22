package org.xmyun.starmate.core.llm.provider

import javax.inject.Inject
import javax.inject.Singleton
import org.xmyun.starmate.core.llm.anthropic.AnthropicClient
import org.xmyun.starmate.core.llm.anthropic.AnthropicMaxTokensMemory
import org.xmyun.starmate.core.llm.http.HttpClientFactory
import org.xmyun.starmate.core.llm.openai.OpenAiCompatClient
import org.xmyun.starmate.core.llm.openai.OpenAiEmbeddingClient
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.EmbeddingClient
import org.xmyun.starmate.core.model.LlmClient
import org.xmyun.starmate.core.model.ProviderConfig
import org.xmyun.starmate.core.model.ProviderKind

/** 按服务商类型构造客户端。契约里没有这个接口，模块内部用。 */
interface LlmClientFactory {
    fun chat(
        provider: ProviderConfig,
        apiKey: String,
        model: String,
        supportsTools: Boolean,
        supportsImages: Boolean,
    ): LlmClient

    /** Anthropic 没有 embedding 接口，返回 null。 */
    fun embedding(provider: ProviderConfig, apiKey: String, model: String): EmbeddingClient?
}

@Singleton
class DefaultLlmClientFactory @Inject constructor(
    private val httpFactory: HttpClientFactory,
    private val diag: DiagLog,
    private val maxTokensMemory: AnthropicMaxTokensMemory,
) : LlmClientFactory {

    override fun chat(
        provider: ProviderConfig,
        apiKey: String,
        model: String,
        supportsTools: Boolean,
        supportsImages: Boolean,
    ): LlmClient = when (provider.kind) {
        ProviderKind.OPENAI_COMPAT ->
            OpenAiCompatClient(provider.baseUrl, apiKey, model, supportsTools, supportsImages, httpFactory.client, diag)
        ProviderKind.ANTHROPIC ->
            AnthropicClient(
                provider.baseUrl, apiKey, model, supportsTools, supportsImages, httpFactory.client, diag, maxTokensMemory,
            )
    }

    override fun embedding(provider: ProviderConfig, apiKey: String, model: String): EmbeddingClient? =
        when (provider.kind) {
            ProviderKind.OPENAI_COMPAT -> OpenAiEmbeddingClient(provider.baseUrl, apiKey, model, httpFactory.client, diag)
            ProviderKind.ANTHROPIC -> null
        }
}
