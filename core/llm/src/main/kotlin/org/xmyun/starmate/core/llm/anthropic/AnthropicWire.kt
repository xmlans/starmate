package org.xmyun.starmate.core.llm.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.xmyun.starmate.core.llm.http.MessageShaper
import org.xmyun.starmate.core.model.LlmMessage
import org.xmyun.starmate.core.model.LlmRequest
import org.xmyun.starmate.core.model.Role

/** Anthropic Messages API 的请求构造和响应字段读取。 */
internal object AnthropicWire {

    /** 当前 API 版本（claude-api skill）。这个版本下请求带 temperature/top_p/top_k 会 400。 */
    const val API_VERSION = "2026-10-21"

    /** 官方地址下的默认 max_tokens：流式请求按 skill 建议给足空间（思考也计入）。 */
    const val DEFAULT_MAX_TOKENS_OFFICIAL = 64_000

    /** 第三方 Anthropic 兼容端点（中转站、DeepSeek 等）常见上限 8192，给高了会 400。 */
    const val DEFAULT_MAX_TOKENS_COMPAT = 8_192

    /** 没有 assistant 预填可用时，用 system 末尾一块不缓存的说明要求 JSON。 */
    const val JSON_ONLY_INSTRUCTION =
        "Respond with a single valid JSON object only. Do not wrap it in code fences or add any other text."

    /**
     * @param defaultMaxTokens [LlmRequest.maxOutputTokens] 为空时使用。
     * @param eagerToolStreaming 只对官方地址开启：工具参数边生成边流出；中转站可能不认这个字段。
     */
    fun messagesRequest(
        model: String,
        request: LlmRequest,
        defaultMaxTokens: Int,
        eagerToolStreaming: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", request.maxOutputTokens ?: defaultMaxTokens)
        put("stream", true)
        val system = systemBlocks(request)
        if (system.isNotEmpty()) put("system", system)
        put("messages", messages(MessageShaper.ensureUserFirst(MessageShaper.prepare(request))))
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                for (tool in request.tools) {
                    addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.parametersSchema)
                        if (eagerToolStreaming) put("eager_input_streaming", true)
                    }
                }
            }
        }
        // temperature 在 API 2026-10-21 下一律 400，这里不发
    }

    /**
     * 第一块是 stable，带 cache_control；volatile 和其余部分都放在缓存断点之后。
     * 空文本块会被 API 拒绝，所以空的不发。
     */
    fun systemBlocks(request: LlmRequest): JsonArray = buildJsonArray {
        val stable = request.system.stable
        if (stable.isNotEmpty()) {
            addJsonObject {
                put("type", "text")
                put("text", stable)
                putJsonObject("cache_control") { put("type", "ephemeral") }
            }
        }
        val tail = buildList {
            if (request.system.volatile.isNotBlank()) add(request.system.volatile)
            // 消息列表里的 SYSTEM 消息并入顶层 system；不是所有模型都支持对话中途的 system 消息
            request.messages.filter { it.role == Role.SYSTEM && it.text.isNotBlank() }.forEach { add(it.text) }
            if (request.jsonOutput) add(JSON_ONLY_INSTRUCTION)
        }
        for (text in tail) {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
        }
    }

    /** 转成 Anthropic 消息；相邻的用户侧消息合并成一条（并行工具结果必须放在同一条 user 消息里）。 */
    fun messages(messages: List<LlmMessage>): JsonArray {
        val merged = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        for (message in messages) {
            val (role, blocks) = when (message.role) {
                Role.SYSTEM -> continue
                Role.USER -> "user" to userBlocks(message)
                Role.ASSISTANT -> "assistant" to assistantBlocks(message)
                Role.TOOL -> "user" to listOf(toolResultBlock(message))
            }
            if (blocks.isEmpty()) continue
            val last = merged.lastOrNull()
            // 只合并用户侧（工具结果 + 用户文字）；助手消息能否合并已由 MessageShaper 决定
            if (last != null && last.first == role && role == "user") {
                last.second += blocks
            } else {
                merged += role to blocks.toMutableList()
            }
        }
        return buildJsonArray {
            for ((role, blocks) in merged) {
                addJsonObject {
                    put("role", role)
                    put("content", JsonArray(blocks))
                }
            }
        }
    }

    private fun userBlocks(message: LlmMessage): List<JsonObject> = buildList {
        // 图片放在文字前面
        for (image in message.images) {
            add(buildJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", image.mimeType)
                    put("data", image.base64)
                }
            })
        }
        if (message.text.isNotEmpty()) add(textBlock(message.text))
    }

    private fun assistantBlocks(message: LlmMessage): List<JsonObject> = buildList {
        if (message.text.isNotEmpty()) add(textBlock(message.text))
        for (call in message.toolCalls) {
            add(buildJsonObject {
                put("type", "tool_use")
                put("id", call.id)
                put("name", call.name)
                put("input", call.arguments)
            })
        }
    }

    private fun toolResultBlock(message: LlmMessage): JsonObject = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", message.toolCallId.orEmpty())
        if (message.text.isNotEmpty()) put("content", message.text)
        if (MessageShaper.isErrorResult(message.text)) put("is_error", true)
    }

    private fun textBlock(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    fun modelIds(body: JsonObject): List<String> =
        (body["data"] as? JsonArray).orEmpty().mapNotNull { item ->
            ((item as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
        }

    // —— 读响应字段的小工具 ——

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
}
