package org.xmyun.starmate.core.llm.openai

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import java.util.UUID
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
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
import org.xmyun.starmate.core.llm.http.splitDataPayloads
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.LlmClient
import org.xmyun.starmate.core.model.LlmEvent
import org.xmyun.starmate.core.model.LlmRequest
import org.xmyun.starmate.core.model.StopReason
import org.xmyun.starmate.core.model.TokenUsage
import org.xmyun.starmate.core.model.ToolCall

/** OpenAI Chat Completions 兼容客户端：OpenAI、DeepSeek、硅基流动、OpenRouter、Gemini 兼容端点、各类中转站。 */
class OpenAiCompatClient(
    private val baseUrl: String,
    private val apiKey: String,
    override val modelId: String,
    override val supportsTools: Boolean,
    override val supportsImages: Boolean,
    private val http: HttpClient,
    private val diag: DiagLog,
) : LlmClient {

    private val url = joinUrl(baseUrl, "/chat/completions")
    private val secrets = listOf(apiKey)

    override fun stream(request: LlmRequest): Flow<LlmEvent> = llmFlow {
        // 先带 stream_options；服务端明确拒绝这个字段时去掉再试一次
        if (!streamOnce(request, includeUsage = true)) {
            diag.log(DIAG_TAG, "${hostOf(url)} 不支持 stream_options，去掉后重试")
            streamOnce(request, includeUsage = false)
        }
    }

    /** 返回 false 表示服务端拒绝了 stream_options，需要重试。 */
    private suspend fun ProducerScope<LlmEvent>.streamOnce(request: LlmRequest, includeUsage: Boolean): Boolean {
        val body = OpenAiWire.chatRequest(
            model = modelId,
            request = request,
            includeUsage = includeUsage,
            useMaxCompletionTokens = hostOf(url) == "api.openai.com",
        )
        return http.preparePost(url) {
            if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }.execute { response ->
            val status = response.status.value
            diag.http("POST", url, status)
            if (!response.status.isSuccess()) {
                val text = response.errorBody()
                if (includeUsage && status == 400 && "stream_options" in text) return@execute false
                throw ErrorMapper.fromStatus(status, text, secrets)
            }
            val state = OpenAiStreamState(diag, secrets)
            if (response.isJson()) {
                // 无视 stream:true 直接回整段 JSON 的服务商
                val obj = parseObjectOrNull(response.bodyAsText())
                    ?: throw ErrorMapper.badResponse("整段 JSON 无法解析")
                state.onPayload(obj).forEach { send(it) }
                state.finish().forEach { send(it) }
                return@execute true
            }
            SseReader.events(response.bodyAsChannel()).collect { event ->
                if (state.done) return@collect
                for (payload in splitDataPayloads(event.data)) {
                    if (payload.trim() == "[DONE]") {
                        state.finish().forEach { send(it) }
                        break
                    }
                    val obj = parseObjectOrNull(payload)
                        ?: throw ErrorMapper.badResponse("流里的数据块无法解析")
                    state.onPayload(obj).forEach { send(it) }
                }
            }
            if (!state.done) {
                // 没有 [DONE] 就断开：收到过有效数据块时照常收尾，否则算响应不对
                if (!state.sawChunk) throw ErrorMapper.badResponse("没有收到任何数据")
                state.finish().forEach { send(it) }
            }
            true
        }
    }

    override fun toString(): String = "OpenAiCompatClient(host=${hostOf(url)}, model=$modelId)"
}

/** 一次流式回复的累积状态。纯逻辑，方便单测。 */
internal class OpenAiStreamState(
    private val diag: DiagLog,
    private val secrets: Collection<String>,
) {
    private class ToolAcc(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    private val tools = sortedMapOf<Int, ToolAcc>()
    private var toolsFlushed = false
    private var emittedToolCount = 0
    private var usage: TokenUsage? = null
    private var finishReason: String? = null
    private val textGuard = TextRepeatGuard()
    private var repeatLogged = false

    var sawChunk = false
        private set
    var done = false
        private set

    fun onPayload(obj: JsonObject): List<LlmEvent> {
        val error = obj["error"]
        if (error != null && error !is kotlinx.serialization.json.JsonNull) {
            throw ErrorMapper.fromStreamError(runCatching { error.jsonObject }.getOrNull(), secrets)
        }
        val chunk = try {
            LlmJson.decodeFromJsonElement<ChatChunk>(obj)
        } catch (e: Exception) {
            throw ErrorMapper.badResponse("数据块结构不对", e)
        }
        sawChunk = true
        chunk.usage?.let { usage = it.toTokenUsage() }
        val events = mutableListOf<LlmEvent>()
        val choice = chunk.choices.firstOrNull { it.index == 0 } ?: chunk.choices.firstOrNull() ?: return events
        val delta = choice.delta
        val message = choice.message
        when {
            delta != null -> {
                delta.content?.takeIf { it.isNotEmpty() }?.let { text(textGuard.delta(it), events) }
                delta.toolCalls?.forEach { accumulate(it) }
            }
            // 只带 message 的数据块是一份整段回复：不回整段 JSON 的服务商只发这一块，
            // 有的中转站则在增量发完之后再补一块，后者只取还没收到的部分
            message != null -> {
                message.content?.takeIf { it.isNotEmpty() }?.let { text(textGuard.snapshot(it), events) }
                if (tools.isEmpty()) message.toolCalls?.forEach { accumulate(it) }
            }
        }
        choice.finishReason?.let {
            finishReason = it
            events += flushTools()
        }
        return events
    }

    private fun text(text: String, events: MutableList<LlmEvent>) {
        if (textGuard.repeated && !repeatLogged) {
            repeatLogged = true
            diag.log(DIAG_TAG, "服务端把已收到的回复文字又发了一遍，已去掉重复的部分")
        }
        if (text.isNotEmpty()) events += LlmEvent.TextDelta(text)
    }

    private fun accumulate(delta: WireToolCallDelta) {
        val index = delta.index
            ?: delta.id?.let { id -> tools.entries.firstOrNull { it.value.id == id }?.key ?: tools.size }
            ?: tools.keys.lastOrNull()
            ?: 0
        val acc = tools.getOrPut(index) { ToolAcc() }
        delta.id?.takeIf { it.isNotEmpty() }?.let { acc.id = it }
        delta.function?.name?.takeIf { it.isNotEmpty() && acc.name.isEmpty() }?.let { acc.name = it }
        delta.function?.arguments?.let { acc.args.append(it) }
    }

    private fun flushTools(): List<LlmEvent> {
        if (toolsFlushed) return emptyList()
        toolsFlushed = true
        return tools.values.mapNotNull { acc ->
            if (acc.name.isEmpty()) {
                diag.log(DIAG_TAG, "丢弃一个没有名字的工具调用")
                return@mapNotNull null
            }
            emittedToolCount++
            val id = acc.id.ifEmpty { "call_" + UUID.randomUUID().toString().replace("-", "").take(24) }
            LlmEvent.ToolCallRequested(ToolCall(id, acc.name, parseArguments(acc.name, acc.args.toString())))
        }
    }

    private fun parseArguments(name: String, raw: String): JsonObject {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching { LlmJson.parseToJsonElement(raw).jsonObject }.getOrElse {
            diag.log(DIAG_TAG, "工具 $name 的参数不是合法 JSON，按空对象处理")
            JsonObject(emptyMap())
        }
    }

    /** 收尾：补发还没发的工具调用，再发 Completed。只生效一次。 */
    fun finish(): List<LlmEvent> {
        if (done) return emptyList()
        done = true
        val events = flushTools().toMutableList()
        events += LlmEvent.Completed(usage ?: TokenUsage(), stopReason())
        return events
    }

    private fun stopReason(): StopReason = when (finishReason) {
        "tool_calls", "function_call" -> StopReason.TOOL_CALLS
        "length" -> StopReason.MAX_TOKENS
        // 有的服务商发了工具调用却报 stop
        "stop", null -> if (emittedToolCount > 0) StopReason.TOOL_CALLS else StopReason.END
        else -> StopReason.OTHER
    }

    private fun WireUsage.toTokenUsage() = TokenUsage(
        inputTokens = promptTokens,
        outputTokens = completionTokens,
        cachedInputTokens = promptTokensDetails?.cachedTokens ?: promptCacheHitTokens ?: 0,
    )
}
