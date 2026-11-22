package org.xmyun.starmate.core.llm.anthropic

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import org.xmyun.starmate.core.model.LlmException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.xmyun.starmate.core.llm.anthropic.AnthropicWire.int
import org.xmyun.starmate.core.llm.anthropic.AnthropicWire.obj
import org.xmyun.starmate.core.llm.anthropic.AnthropicWire.str
import org.xmyun.starmate.core.llm.http.DIAG_TAG
import org.xmyun.starmate.core.llm.http.ErrorMapper
import org.xmyun.starmate.core.llm.http.LlmJson
import org.xmyun.starmate.core.llm.http.SseReader
import org.xmyun.starmate.core.llm.http.TextRepeatGuard
import org.xmyun.starmate.core.llm.http.errorBody
import org.xmyun.starmate.core.llm.http.hostOf
import org.xmyun.starmate.core.llm.http.http
import org.xmyun.starmate.core.llm.http.isJson
import org.xmyun.starmate.core.llm.http.joinUrl
import org.xmyun.starmate.core.llm.http.llmFlow
import org.xmyun.starmate.core.llm.http.parseObjectOrNull
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.LlmClient
import org.xmyun.starmate.core.model.LlmEvent
import org.xmyun.starmate.core.model.LlmRequest
import org.xmyun.starmate.core.model.StopReason
import org.xmyun.starmate.core.model.TokenUsage
import org.xmyun.starmate.core.model.ToolCall

/** Anthropic 原生 Messages API 客户端（流式、工具调用、system 稳定部分打缓存标记）。 */
class AnthropicClient(
    private val baseUrl: String,
    private val apiKey: String,
    override val modelId: String,
    override val supportsTools: Boolean,
    override val supportsImages: Boolean,
    private val http: HttpClient,
    private val diag: DiagLog,
    private val maxTokensMemory: AnthropicMaxTokensMemory = AnthropicMaxTokensMemory(),
) : LlmClient {

    private val url = joinUrl(baseUrl, "/v1/messages")
    private val secrets = listOf(apiKey)
    private val official = hostOf(url) == OFFICIAL_HOST
    private val memoryKey = AnthropicMaxTokensMemory.key(baseUrl, modelId)

    override fun stream(request: LlmRequest): Flow<LlmEvent> = llmFlow {
        if (request.temperature != null) diag.log(DIAG_TAG, "Anthropic API $API_VERSION 不接受 temperature，已忽略")
        if (request.maxOutputTokens != null) {
            // 调用方显式给了值：不降档，出错原样抛出
            streamOnce(request, request.maxOutputTokens!!, allowDowngrade = false)
            return@llmFlow
        }
        val default = if (official) AnthropicWire.DEFAULT_MAX_TOKENS_OFFICIAL else AnthropicWire.DEFAULT_MAX_TOKENS_COMPAT
        val start = maxTokensMemory.get(memoryKey) ?: default
        var maxTokens = start
        while (true) {
            val overLimit = streamOnce(request, maxTokens, allowDowngrade = true) ?: break
            // 收到 max_tokens 超限：用错误里给出的上限，否则降到下一档；没有更低的档就原样抛出
            val next = overLimit.allowed?.takeIf { it in 1 until maxTokens }
                ?: AnthropicMaxTokensMemory.LADDER.firstOrNull { it < maxTokens }
                ?: throw overLimit.error
            diag.log(DIAG_TAG, "$modelId max_tokens $maxTokens 超限，降到 $next 重试")
            maxTokens = next
        }
        // 这次降过档才更新记忆
        if (maxTokens != start) maxTokensMemory.put(memoryKey, maxTokens)
    }

    private class OverLimit(val allowed: Int?, val error: LlmException)

    /**
     * 发一次请求并把事件送下游。正常结束返回 null；
     * [allowDowngrade] 时遇到 max_tokens 超限的 400 不抛错，返回 [OverLimit]。
     */
    private suspend fun ProducerScope<LlmEvent>.streamOnce(
        request: LlmRequest,
        maxTokens: Int,
        allowDowngrade: Boolean,
    ): OverLimit? {
        val body = AnthropicWire.messagesRequest(
            model = modelId,
            request = request.copy(maxOutputTokens = maxTokens),
            defaultMaxTokens = maxTokens,
            eagerToolStreaming = official,
        )
        return http.preparePost(url) {
            if (apiKey.isNotBlank()) header("x-api-key", apiKey)
            header("anthropic-version", API_VERSION)
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }.execute { response ->
            val status = response.status.value
            diag.http("POST", url, status)
            if (!response.status.isSuccess()) {
                val text = response.errorBody()
                val error = ErrorMapper.fromStatus(status, text, secrets)
                if (allowDowngrade && status == 400 && AnthropicMaxTokensMemory.isMaxTokensError(text)) {
                    return@execute OverLimit(AnthropicMaxTokensMemory.parseLimit(text), error)
                }
                throw error
            }
            val state = AnthropicStreamState(diag, secrets)
            if (response.isJson()) {
                val obj = parseObjectOrNull(response.bodyAsText())
                    ?: throw ErrorMapper.badResponse("整段 JSON 无法解析")
                state.onFullMessage(obj).forEach { send(it) }
                return@execute null
            }
            SseReader.events(response.bodyAsChannel()).collect { event ->
                if (state.done) return@collect
                val obj = parseObjectOrNull(event.data) ?: throw ErrorMapper.badResponse("流里的数据块无法解析")
                state.onEvent(event.event, obj).forEach { send(it) }
            }
            if (!state.done) state.onEndOfStream().forEach { send(it) }
            null
        }
    }

    override fun toString(): String = "AnthropicClient(host=${hostOf(url)}, model=$modelId)"

    companion object {
        const val API_VERSION = AnthropicWire.API_VERSION
        const val OFFICIAL_HOST = "api.anthropic.com"
    }
}

/** 一次流式回复的累积状态。思考块（thinking、redacted_thinking）和服务端工具块直接忽略。 */
internal class AnthropicStreamState(
    private val diag: DiagLog,
    private val secrets: Collection<String>,
) {
    private class ToolAcc(val id: String, val name: String, val initialInput: JsonObject?, val json: StringBuilder = StringBuilder())

    private val tools = mutableMapOf<Int, ToolAcc>()
    private var inputTokens = 0
    private var cacheReadTokens = 0
    private var cacheWriteTokens = 0
    private var outputTokens = 0
    private var stopReason: String? = null
    private var started = false
    private val textGuard = TextRepeatGuard()
    private var repeatLogged = false

    var done = false
        private set

    fun onEvent(eventName: String?, data: JsonObject): List<LlmEvent> {
        return when (data.str("type") ?: eventName) {
            "message_start" -> {
                started = true
                data.obj("message")?.obj("usage")?.let { readUsage(it) }
                emptyList()
            }
            "content_block_start" -> {
                val index = data.int("index") ?: 0
                val block = data.obj("content_block")
                if (block?.str("type") == "tool_use") {
                    tools[index] = ToolAcc(block.str("id").orEmpty(), block.str("name").orEmpty(), block.obj("input"))
                }
                emptyList()
            }
            "content_block_delta" -> {
                val index = data.int("index") ?: 0
                val delta = data.obj("delta")
                when (delta?.str("type")) {
                    "text_delta" -> delta.str("text")?.takeIf { it.isNotEmpty() }?.let { text(textGuard.delta(it)) }.orEmpty()
                    "input_json_delta" -> {
                        tools[index]?.json?.append(delta.str("partial_json").orEmpty())
                        emptyList()
                    }
                    else -> emptyList() // thinking_delta、signature_delta 等
                }
            }
            "content_block_stop" -> {
                val index = data.int("index") ?: 0
                val acc = tools.remove(index) ?: return emptyList()
                listOf(toolEvent(acc))
            }
            "message_delta" -> {
                data.obj("delta")?.str("stop_reason")?.let { stopReason = it }
                data.obj("usage")?.let { readUsage(it) }
                emptyList()
            }
            "message_stop" -> complete()
            "error" -> throw ErrorMapper.fromStreamError(data.obj("error"), secrets)
            else -> emptyList() // ping 以及以后新增的事件
        }
    }

    private fun text(text: String): List<LlmEvent> {
        if (textGuard.repeated && !repeatLogged) {
            repeatLogged = true
            diag.log(DIAG_TAG, "服务端把已收到的回复文字又发了一遍，已去掉重复的部分")
        }
        return if (text.isEmpty()) emptyList() else listOf(LlmEvent.TextDelta(text))
    }

    /** 流断开但没收到 message_stop：已经拿到 stop_reason 就照常收尾，否则算响应不完整。 */
    fun onEndOfStream(): List<LlmEvent> {
        if (!started || stopReason == null) throw ErrorMapper.badResponse("流在 message_stop 之前结束")
        return tools.keys.sorted().map { toolEvent(tools.remove(it)!!) } + complete()
    }

    /** 中转站无视 stream:true、直接回完整 message 时用。 */
    fun onFullMessage(message: JsonObject): List<LlmEvent> {
        if (message.str("type") == "error") throw ErrorMapper.fromStreamError(message.obj("error"), secrets)
        val content = message["content"] as? JsonArray ?: throw ErrorMapper.badResponse("缺少 content")
        started = true
        message.obj("usage")?.let { readUsage(it) }
        stopReason = message.str("stop_reason")
        val events = mutableListOf<LlmEvent>()
        for (element in content) {
            val block = element as? JsonObject ?: continue
            when (block.str("type")) {
                "text" -> block.str("text")?.takeIf { it.isNotEmpty() }?.let { events += LlmEvent.TextDelta(it) }
                "tool_use" -> events += toolEvent(ToolAcc(block.str("id").orEmpty(), block.str("name").orEmpty(), block.obj("input")))
            }
        }
        return events + complete()
    }

    private fun toolEvent(acc: ToolAcc): LlmEvent {
        val raw = acc.json.toString()
        val input = when {
            raw.isNotBlank() -> runCatching { LlmJson.parseToJsonElement(raw).jsonObject }.getOrElse {
                diag.log(DIAG_TAG, "工具 ${acc.name} 的参数不是合法 JSON，按空对象处理")
                JsonObject(emptyMap())
            }
            else -> acc.initialInput ?: JsonObject(emptyMap())
        }
        return LlmEvent.ToolCallRequested(ToolCall(acc.id, acc.name, input))
    }

    /** message_start 给输入侧用量，message_delta 给累计输出（新版本也可能带累计输入），后到的覆盖先到的。 */
    private fun readUsage(usage: JsonObject) {
        usage.int("input_tokens")?.let { inputTokens = it }
        usage.int("cache_read_input_tokens")?.let { cacheReadTokens = it }
        usage.int("cache_creation_input_tokens")?.let { cacheWriteTokens = it }
        usage.int("output_tokens")?.let { outputTokens = it }
    }

    private fun complete(): List<LlmEvent> {
        if (done) return emptyList()
        done = true
        val usage = TokenUsage(
            // input_tokens 不含缓存读写，这里合并成总输入
            inputTokens = inputTokens + cacheReadTokens + cacheWriteTokens,
            outputTokens = outputTokens,
            cachedInputTokens = cacheReadTokens,
        )
        val reason = when (stopReason) {
            "end_turn" -> StopReason.END
            "tool_use" -> StopReason.TOOL_CALLS
            "max_tokens" -> StopReason.MAX_TOKENS
            else -> StopReason.OTHER
        }
        return listOf(LlmEvent.Completed(usage, reason))
    }
}
