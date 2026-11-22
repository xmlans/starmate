package org.xmyun.starmate.core.llm.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.xmyun.starmate.core.llm.http.MessageShaper
import org.xmyun.starmate.core.model.LlmMessage
import org.xmyun.starmate.core.model.LlmRequest
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.SystemPrompt

/** OpenAI Chat Completions 的请求构造和响应结构。 */
internal object OpenAiWire {

    /**
     * @param includeUsage 发 `stream_options.include_usage`；有的服务商不认，被拒后去掉重发。
     * @param useMaxCompletionTokens OpenAI 官方新模型只认 `max_completion_tokens`，其他兼容服务商多数只认 `max_tokens`。
     */
    fun chatRequest(
        model: String,
        request: LlmRequest,
        includeUsage: Boolean,
        useMaxCompletionTokens: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("messages", messages(request))
        put("stream", true)
        if (includeUsage) putJsonObject("stream_options") { put("include_usage", true) }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                for (tool in request.tools) {
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parametersSchema)
                        }
                    }
                }
            }
        }
        if (request.jsonOutput) putJsonObject("response_format") { put("type", "json_object") }
        request.maxOutputTokens?.let { put(if (useMaxCompletionTokens) "max_completion_tokens" else "max_tokens", it) }
        request.temperature?.let { put("temperature", it) }
    }

    fun systemText(system: SystemPrompt): String = when {
        system.volatile.isBlank() -> system.stable
        system.stable.isBlank() -> system.volatile
        else -> system.stable + "\n\n" + system.volatile
    }

    fun messages(request: LlmRequest): JsonArray = buildJsonArray {
        val systemText = systemText(request.system)
        if (systemText.isNotBlank()) {
            addJsonObject {
                put("role", "system")
                put("content", systemText)
            }
        }
        for (message in MessageShaper.prepare(request)) add(message(message))
    }

    private fun message(message: LlmMessage): JsonObject = when (message.role) {
        Role.SYSTEM -> buildJsonObject {
            put("role", "system")
            put("content", message.text)
        }
        Role.USER -> buildJsonObject {
            put("role", "user")
            if (message.images.isEmpty()) {
                put("content", message.text)
            } else {
                putJsonArray("content") {
                    if (message.text.isNotEmpty()) {
                        addJsonObject {
                            put("type", "text")
                            put("text", message.text)
                        }
                    }
                    for (image in message.images) {
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:${image.mimeType};base64,${image.base64}") }
                        }
                    }
                }
            }
        }
        Role.ASSISTANT -> buildJsonObject {
            put("role", "assistant")
            if (message.toolCalls.isEmpty()) {
                put("content", message.text)
            } else {
                // 只有工具调用时 content 用 null，部分服务商不接受空串
                put("content", if (message.text.isEmpty()) JsonNull else JsonPrimitive(message.text))
                putJsonArray("tool_calls") {
                    for (call in message.toolCalls) {
                        addJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.arguments.toString())
                            }
                        }
                    }
                }
            }
        }
        Role.TOOL -> buildJsonObject {
            put("role", "tool")
            put("tool_call_id", message.toolCallId.orEmpty())
            put("content", message.text)
        }
    }

    /** 模型列表 `GET /models` 的 data 里取 id。 */
    fun modelIds(body: JsonObject): List<String> =
        (body["data"] as? JsonArray).orEmpty().mapNotNull { item ->
            ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.content
        }
}

@Serializable
internal data class ChatChunk(
    val choices: List<ChunkChoice> = emptyList(),
    val usage: WireUsage? = null,
)

@Serializable
internal data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta? = null,
    /** 服务商无视 stream:true、直接返回整段 JSON 时在这里。 */
    val message: ChunkDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class ChunkDelta(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCallDelta>? = null,
)

@Serializable
internal data class WireToolCallDelta(
    val index: Int? = null,
    val id: String? = null,
    val function: WireFunctionDelta? = null,
)

@Serializable
internal data class WireFunctionDelta(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
internal data class WireUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null,
    /** DeepSeek 用这个字段报缓存命中。 */
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int? = null,
)

@Serializable
internal data class PromptTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Int? = null,
)

@Serializable
internal data class EmbeddingResponse(val data: List<EmbeddingItem> = emptyList())

@Serializable
internal data class EmbeddingItem(val index: Int? = null, val embedding: List<Float> = emptyList())
