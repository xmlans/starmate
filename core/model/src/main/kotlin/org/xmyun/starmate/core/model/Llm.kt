package org.xmyun.starmate.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ImagePart(val mimeType: String, val base64: String)

data class ToolCall(val id: String, val name: String, val arguments: JsonObject)

data class ToolSpec(val name: String, val description: String, val parametersSchema: JsonObject)

data class LlmMessage(
    val role: Role,
    val text: String = "",
    val images: List<ImagePart> = emptyList(),   // 仅 USER
    val toolCalls: List<ToolCall> = emptyList(), // 仅 ASSISTANT
    val toolCallId: String? = null,              // 仅 TOOL
)

/** stable 在内容不变时必须逐字不变，客户端可以对它做提示词缓存；volatile 每轮都可能变。 */
data class SystemPrompt(val stable: String, val volatile: String = "")

data class LlmRequest(
    val system: SystemPrompt,
    val messages: List<LlmMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    val jsonOutput: Boolean = false,
)

data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cachedInputTokens + other.cachedInputTokens,
    )
}

enum class StopReason { END, TOOL_CALLS, MAX_TOKENS, OTHER }

sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent

    /** 一次完整的工具调用，参数已拼好。 */
    data class ToolCallRequested(val call: ToolCall) : LlmEvent

    /** 流的最后一个事件。 */
    data class Completed(val usage: TokenUsage, val stopReason: StopReason) : LlmEvent
}

enum class LlmErrorKind {
    NOT_CONFIGURED, INVALID_KEY, QUOTA_OR_RATE_LIMIT, MODEL_NOT_FOUND,
    TOOLS_UNSUPPORTED, UNREACHABLE, TIMEOUT, BAD_RESPONSE, UNKNOWN,
}

class LlmException(
    val kind: LlmErrorKind,
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

interface LlmClient {
    val modelId: String
    val supportsTools: Boolean
    val supportsImages: Boolean

    /** 冷流。出错时在收集处抛出 [LlmException]。 */
    fun stream(request: LlmRequest): Flow<LlmEvent>
}

interface EmbeddingClient {
    val modelId: String
    suspend fun embed(texts: List<String>): List<FloatArray>
}

/** TTS、STT 这类非聊天端点的原始配置。 */
data class EndpointConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val extras: Map<String, String> = emptyMap(),
) {
    // 数据类默认的 toString 会带出 key，这里盖掉，防止被打进日志或异常消息。
    override fun toString(): String =
        "EndpointConfig(baseUrl=$baseUrl, apiKey=***, model=$model, extras=$extras)"
}

object EndpointExtras {
    const val VOICE = "voice"
    const val FORMAT = "format"
    const val INSTRUCTIONS = "instructions"
}

interface ModelRouter {
    /** 只接受 CHAT、BACKGROUND、VISION。后两者没配置时回落到 CHAT；CHAT 也没配置时返回 null。 */
    suspend fun chat(role: ModelRole): LlmClient?

    suspend fun embedding(): EmbeddingClient?

    /** 只接受 TTS、STT。没配置时返回 null。 */
    suspend fun endpoint(role: ModelRole): EndpointConfig?

    /** 服务端报不支持工具调用后调用，之后该角色的 client.supportsTools 为 false。 */
    suspend fun markToolsUnsupported(role: ModelRole)
}
