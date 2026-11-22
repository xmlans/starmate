package org.xmyun.starmate.core.llm.anthropic

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.xmyun.starmate.core.llm.FakeDiagLog
import org.xmyun.starmate.core.llm.TEST_KEY
import org.xmyun.starmate.core.llm.assertNoKeyLeak
import org.xmyun.starmate.core.llm.enqueueJson
import org.xmyun.starmate.core.llm.enqueueSse
import org.xmyun.starmate.core.llm.ev
import org.xmyun.starmate.core.llm.expectLlmError
import org.xmyun.starmate.core.llm.http.HttpClientFactory
import org.xmyun.starmate.core.llm.http.MessageShaper
import org.xmyun.starmate.core.llm.json
import org.xmyun.starmate.core.model.ImagePart
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.LlmEvent
import org.xmyun.starmate.core.model.LlmMessage
import org.xmyun.starmate.core.model.LlmRequest
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.StopReason
import org.xmyun.starmate.core.model.SystemPrompt
import org.xmyun.starmate.core.model.TokenUsage
import org.xmyun.starmate.core.model.ToolCall
import org.xmyun.starmate.core.model.ToolSpec

class AnthropicClientTest {

    private val server = MockWebServer()
    private val diag = FakeDiagLog()
    private lateinit var http: HttpClient

    private val simpleRequest = LlmRequest(SystemPrompt("你是星伴"), listOf(LlmMessage(Role.USER, "在吗")))

    @Before
    fun setUp() {
        server.start()
        http = HttpClientFactory.create()
    }

    @After
    fun tearDown() {
        http.close()
        server.close()
    }

    private val memory = AnthropicMaxTokensMemory()

    private fun client() =
        AnthropicClient(server.url("/").toString(), TEST_KEY, "claude-opus-5-5", true, true, http, diag, memory)

    private val memoryKey get() = AnthropicMaxTokensMemory.key(server.url("/").toString(), "claude-opus-5-5")

    private fun overLimit(message: String) =
        """{"type":"error","error":{"type":"invalid_request_error","message":"$message"}}"""

    private fun okStream() = messageStart + blockStart(0, """{"type":"text","text":""}""") +
        blockDelta(0, """{"type":"text_delta","text":"好"}""") + blockStop(0) + messageDelta("end_turn", 1) + messageStop

    private fun maxTokensOf(index: Int): Int {
        repeat(index) { server.takeRequest() }
        return server.takeRequest().json()["max_tokens"]!!.jsonPrimitive.int
    }

    @Test
    fun maxTokensOverLimitDowngradesToParsedLimitAndIsRemembered() = runTest {
        server.enqueueJson(400, overLimit("max_tokens: 8192 > 2048, which is the maximum allowed number of output tokens for claude-opus-5-5"))
        server.enqueueSse(okStream())

        val events = client().stream(simpleRequest).toList()

        assertEquals(LlmEvent.TextDelta("好"), events.first())
        assertEquals(StopReason.END, (events.last() as LlmEvent.Completed).stopReason)
        assertEquals(8192, server.takeRequest().json()["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(2048, server.takeRequest().json()["max_tokens"]!!.jsonPrimitive.int)
        assertTrue(diag.lines.any { "claude-opus-5-5" in it && "2048" in it })

        // 新建的客户端共用同一份记忆，第一个请求就用记住的值
        server.enqueueSse(okStream())
        client().stream(simpleRequest).toList()
        assertEquals(2048, server.takeRequest().json()["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun maxTokensLadderWhenLimitNotInMessage() = runTest {
        memory.put(memoryKey, 32_000)
        server.enqueueJson(400, overLimit("max_tokens is too large for this model"))
        server.enqueueJson(400, overLimit("max_tokens is too large for this model"))
        server.enqueueSse(okStream())

        val events = client().stream(simpleRequest).toList()

        assertTrue(events.last() is LlmEvent.Completed)
        assertEquals(32_000, maxTokensOf(0))
        assertEquals(16_000, maxTokensOf(0))
        assertEquals(8_192, maxTokensOf(0))
        assertEquals(8_192, memory.get(memoryKey))
    }

    @Test
    fun maxTokensLadderExhaustedThrows() = runTest {
        memory.put(memoryKey, 4_096)
        server.enqueueJson(400, overLimit("max_tokens is too large for this model"))

        val e = client().stream(simpleRequest).expectLlmError()

        assertEquals(400, e.httpStatus)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun explicitMaxTokensNeverDowngrades() = runTest {
        server.enqueueJson(400, overLimit("max_tokens: 8192 > 2048, which is the maximum allowed number of output tokens"))

        val e = client().stream(simpleRequest.copy(maxOutputTokens = 8192)).expectLlmError()

        assertEquals(400, e.httpStatus)
        assertEquals(1, server.requestCount)
        assertNull(memory.get(memoryKey))
    }

    @Test
    fun unrelated400DoesNotDowngrade() = runTest {
        server.enqueueJson(400, overLimit("messages.0.content: text content blocks must be non-empty"))

        val e = client().stream(simpleRequest).expectLlmError()

        assertEquals(LlmErrorKind.UNKNOWN, e.kind)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun parseLimitFromCommonMessages() {
        assertEquals(32_000, AnthropicMaxTokensMemory.parseLimit("max_tokens: 64000 > 32000, which is the maximum allowed"))
        assertEquals(8_192, AnthropicMaxTokensMemory.parseLimit("Invalid max_tokens value, the valid range of max_tokens is [1, 8192]"))
        assertEquals(4_096, AnthropicMaxTokensMemory.parseLimit("max_tokens must be less than or equal to 4096"))
        assertNull(AnthropicMaxTokensMemory.parseLimit("max_tokens is too large"))
        assertFalse(AnthropicMaxTokensMemory.isMaxTokensError("messages: roles must alternate"))
        assertTrue(AnthropicMaxTokensMemory.isMaxTokensError("Invalid max_tokens value, the valid range of max_tokens is [1, 8192]"))
    }

    private val messageStart = ev(
        "message_start",
        """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude-opus-5-5","stop_reason":null,"usage":{"input_tokens":12,"cache_creation_input_tokens":5,"cache_read_input_tokens":100,"output_tokens":1}}}""",
    )

    private fun blockStart(index: Int, block: String) =
        ev("content_block_start", """{"type":"content_block_start","index":$index,"content_block":$block}""")

    private fun blockDelta(index: Int, delta: String) =
        ev("content_block_delta", """{"type":"content_block_delta","index":$index,"delta":$delta}""")

    private fun blockStop(index: Int) = ev("content_block_stop", """{"type":"content_block_stop","index":$index}""")

    private fun messageDelta(stop: String, output: Int) = ev(
        "message_delta",
        """{"type":"message_delta","delta":{"stop_reason":"$stop","stop_sequence":null},"usage":{"output_tokens":$output}}""",
    )

    private val messageStop = ev("message_stop", """{"type":"message_stop"}""")

    @Test
    fun deltaResendingTheWholeTextIsNotAppendedAgain() = runTest {
        val reply = "我猜……是自己想做的小东西？那种反正是自己的、怎么折腾都不亏的项目。"
        server.enqueueSse(
            messageStart + blockStart(0, """{"type":"text","text":""}""") +
                blockDelta(0, """{"type":"text_delta","text":"${reply.substring(0, 10)}"}""") +
                blockDelta(0, """{"type":"text_delta","text":"${reply.substring(10)}"}""") +
                // 中转站在结尾又把整段发了一次
                blockDelta(0, """{"type":"text_delta","text":"$reply"}""") +
                blockStop(0) + messageDelta("end_turn", 30) + messageStop,
        )

        val text = client().stream(simpleRequest).toList()
            .filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }

        assertEquals(reply, text)
    }

    @Test
    fun textStreamWithThinkingBlockIgnoredAndUsageMerged() = runTest {
        server.enqueueSse(
            messageStart +
                blockStart(0, """{"type":"thinking","thinking":"","signature":""}""") +
                blockDelta(0, """{"type":"thinking_delta","thinking":""}""") +
                blockDelta(0, """{"type":"signature_delta","signature":"EqQB"}""") +
                blockStop(0) +
                blockStart(1, """{"type":"text","text":""}""") +
                ev("ping", """{"type":"ping"}""") +
                blockDelta(1, """{"type":"text_delta","text":"你好"}""") +
                blockDelta(1, """{"type":"text_delta","text":"呀"}""") +
                blockStop(1) +
                messageDelta("end_turn", 42) +
                messageStop,
            maxChunkSize = 17,
        )

        val events = client().stream(simpleRequest).toList()

        assertEquals(
            listOf(
                LlmEvent.TextDelta("你好"),
                LlmEvent.TextDelta("呀"),
                // 输入 = 12 + 缓存写 5 + 缓存读 100；输出取 message_delta 的累计值
                LlmEvent.Completed(TokenUsage(inputTokens = 117, outputTokens = 42, cachedInputTokens = 100), StopReason.END),
            ),
            events,
        )
    }

    @Test
    fun toolUseStream() = runTest {
        server.enqueueSse(
            messageStart +
                blockStart(0, """{"type":"text","text":""}""") +
                blockDelta(0, """{"type":"text_delta","text":"我查一下"}""") +
                blockStop(0) +
                blockStart(1, """{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{}}""") +
                blockDelta(1, """{"type":"input_json_delta","partial_json":""}""") +
                blockDelta(1, """{"type":"input_json_delta","partial_json":"{\"city\":"}""") +
                blockDelta(1, """{"type":"input_json_delta","partial_json":"\"上海\"}"}""") +
                blockStop(1) +
                messageDelta("tool_use", 20) +
                messageStop,
        )

        val events = client().stream(simpleRequest).toList()

        assertEquals(
            listOf(
                LlmEvent.TextDelta("我查一下"),
                LlmEvent.ToolCallRequested(ToolCall("toolu_1", "get_weather", buildJsonObject { put("city", "上海") })),
                LlmEvent.Completed(TokenUsage(117, 20, 100), StopReason.TOOL_CALLS),
            ),
            events,
        )
    }

    @Test
    fun toolUseWithoutInputDeltasUsesStartInput() = runTest {
        server.enqueueSse(
            messageStart +
                blockStart(0, """{"type":"tool_use","id":"toolu_2","name":"now","input":{}}""") +
                blockStop(0) +
                messageDelta("tool_use", 3) +
                messageStop,
        )

        val events = client().stream(simpleRequest).toList()

        assertEquals(LlmEvent.ToolCallRequested(ToolCall("toolu_2", "now", buildJsonObject { })), events[0])
    }

    @Test
    fun requestBodyShape() = runTest {
        server.enqueueSse(messageStart + messageDelta("end_turn", 1) + messageStop)
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }
        val request = LlmRequest(
            system = SystemPrompt(stable = "准则和人设", volatile = "此刻 21:00"),
            messages = listOf(
                LlmMessage(Role.USER, "看图", images = listOf(ImagePart("image/jpeg", "BBBB"))),
                LlmMessage(
                    Role.ASSISTANT,
                    "我看看",
                    toolCalls = listOf(
                        ToolCall("toolu_a", "t1", buildJsonObject { put("x", 1) }),
                        ToolCall("toolu_b", "t2", buildJsonObject { }),
                    ),
                ),
                LlmMessage(Role.TOOL, "结果一", toolCallId = "toolu_a"),
                LlmMessage(Role.TOOL, "结果二", toolCallId = "toolu_b"),
                LlmMessage(Role.USER, "然后呢"),
            ),
            tools = listOf(ToolSpec("t1", "工具一", schema)),
            temperature = 0.7,
        )

        client().stream(request).toList()

        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.url.encodedPath)
        assertEquals(TEST_KEY, recorded.headers["x-api-key"])
        assertEquals("2026-10-21", recorded.headers["anthropic-version"])
        assertNull(recorded.headers["Authorization"])

        val body = recorded.json()
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        // 非官方地址的默认值
        assertEquals(AnthropicWire.DEFAULT_MAX_TOKENS_COMPAT, body["max_tokens"]!!.jsonPrimitive.int)
        // API 2026-10-21 不接受 temperature
        assertNull(body["temperature"])

        val system = body["system"]!!.jsonArray
        assertEquals(2, system.size)
        assertEquals("准则和人设", system[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", system[0].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("此刻 21:00", system[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertNull(system[1].jsonObject["cache_control"])

        val messages = body["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        val user = messages[0].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("image", "text"), user.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("BBBB", user[0].jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)

        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val aBlocks = assistant["content"]!!.jsonArray
        assertEquals(listOf("text", "tool_use", "tool_use"), aBlocks.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals(1, aBlocks[1].jsonObject["input"]!!.jsonObject["x"]!!.jsonPrimitive.int)

        // 两个工具结果和随后的用户文字合并进同一条 user 消息，工具结果在前
        val toolTurn = messages[2].jsonObject
        assertEquals("user", toolTurn["role"]!!.jsonPrimitive.content)
        val tBlocks = toolTurn["content"]!!.jsonArray
        assertEquals(listOf("tool_result", "tool_result", "text"), tBlocks.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("toolu_b", tBlocks[1].jsonObject["tool_use_id"]!!.jsonPrimitive.content)

        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("t1", tool["name"]!!.jsonPrimitive.content)
        assertTrue(tool.containsKey("input_schema"))
        // 只有官方地址才开 eager_input_streaming
        assertFalse(tool.containsKey("eager_input_streaming"))
    }

    @Test
    fun toolHistoryFlattenedWhenNoToolsGiven() = runTest {
        server.enqueueSse(messageStart + messageDelta("end_turn", 1) + messageStop)
        val request = LlmRequest(
            system = SystemPrompt("s"),
            messages = listOf(
                LlmMessage(Role.USER, "明天有什么安排"),
                LlmMessage(
                    Role.ASSISTANT,
                    "",
                    toolCalls = listOf(ToolCall("toolu_1", "calendar_list_events", buildJsonObject { put("day", "tomorrow") })),
                ),
                LlmMessage(Role.TOOL, "下午三点开会", toolCallId = "toolu_1"),
            ),
            tools = emptyList(),
        )

        client().stream(request).toList()

        val body = server.takeRequest().json()
        assertNull(body["tools"])
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        val blockTypes = messages.flatMap { m -> m["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content } }
        assertTrue("不应有原生工具块：$blockTypes", blockTypes.all { it == "text" })
        assertEquals(listOf("user", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        val assistantText = messages[1]["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(assistantText.contains("calendar_list_events"))
        val resultText = messages[2]["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(resultText.contains("calendar_list_events") && resultText.contains("下午三点开会"))
    }

    @Test
    fun historyStartingWithAssistantGetsPlaceholderUserAndUsersAreMerged() = runTest {
        server.enqueueSse(messageStart + messageDelta("end_turn", 1) + messageStop)
        val request = LlmRequest(
            SystemPrompt("s"),
            listOf(
                LlmMessage(Role.ASSISTANT, "早呀，今天也要元气满满"),
                LlmMessage(Role.USER, "早"),
                LlmMessage(Role.USER, "刚醒"),
            ),
        )

        client().stream(request).toList()

        val messages = server.takeRequest().json()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(
            MessageShaper.PLACEHOLDER_USER_TEXT,
            messages[0]["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        val lastUser = messages[2]["content"]!!.jsonArray
        assertEquals(1, lastUser.size)
        assertEquals("早\n\n刚醒", lastUser[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorPrefixedToolResultsGetIsError() = runTest {
        server.enqueueSse(messageStart + messageDelta("end_turn", 1) + messageStop)
        val tool = ToolSpec("t", "d", buildJsonObject { put("type", "object") })
        val request = LlmRequest(
            SystemPrompt("s"),
            listOf(
                LlmMessage(Role.USER, "试试"),
                LlmMessage(
                    Role.ASSISTANT,
                    "",
                    toolCalls = listOf(
                        ToolCall("a", "t", buildJsonObject { }),
                        ToolCall("b", "t", buildJsonObject { }),
                        ToolCall("c", "t", buildJsonObject { }),
                    ),
                ),
                LlmMessage(Role.TOOL, "[出错] 没有权限", toolCallId = "a"),
                LlmMessage(Role.TOOL, "[error] timeout", toolCallId = "b"),
                LlmMessage(Role.TOOL, "正常结果", toolCallId = "c"),
            ),
            tools = listOf(tool),
        )

        client().stream(request).toList()

        val results = server.takeRequest().json()["messages"]!!.jsonArray[2].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(true, true, null), results.map { it["is_error"]?.jsonPrimitive?.boolean })
        assertEquals("[出错] 没有权限", results[0]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun explicitMaxTokensIsUsed() = runTest {
        server.enqueueSse(messageStart + messageDelta("max_tokens", 8) + messageStop)

        val events = client().stream(simpleRequest.copy(maxOutputTokens = 8)).toList()

        assertEquals(8, server.takeRequest().json()["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(StopReason.MAX_TOKENS, (events.last() as LlmEvent.Completed).stopReason)
    }

    @Test
    fun officialHostDefaultsAndEagerStreaming() {
        val tool = ToolSpec("t", "d", buildJsonObject { put("type", "object") })
        val body = AnthropicWire.messagesRequest(
            model = "claude-opus-5-5",
            request = simpleRequest.copy(tools = listOf(tool)),
            defaultMaxTokens = AnthropicWire.DEFAULT_MAX_TOKENS_OFFICIAL,
            eagerToolStreaming = true,
        )
        assertEquals(64_000, body["max_tokens"]!!.jsonPrimitive.int)
        assertTrue(body["tools"]!!.jsonArray[0].jsonObject["eager_input_streaming"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun emptySystemIsOmittedAndJsonOutputAddsUncachedInstruction() {
        val empty = AnthropicWire.messagesRequest("m", LlmRequest(SystemPrompt(""), listOf(LlmMessage(Role.USER, "hi"))), 10, false)
        assertNull(empty["system"])

        val json = AnthropicWire.messagesRequest("m", simpleRequest.copy(jsonOutput = true), 10, false)
        val system = json["system"]!!.jsonArray
        assertEquals(AnthropicWire.JSON_ONLY_INSTRUCTION, system.last().jsonObject["text"]!!.jsonPrimitive.content)
        assertNull(system.last().jsonObject["cache_control"])
    }

    @Test
    fun errorStatusAndNoKeyLeak() = runTest {
        server.enqueueJson(
            401,
            """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key $TEST_KEY"}}""",
        )

        val c = client()
        val e = c.stream(simpleRequest).expectLlmError()

        assertEquals(LlmErrorKind.INVALID_KEY, e.kind)
        assertNoKeyLeak(e, diag)
        assertFalse(c.toString().contains(TEST_KEY))
    }

    @Test
    fun notFoundAndRateLimit() = runTest {
        server.enqueueJson(404, """{"type":"error","error":{"type":"not_found_error","message":"model: claude-x"}}""")
        assertEquals(LlmErrorKind.MODEL_NOT_FOUND, client().stream(simpleRequest).expectLlmError().kind)

        server.enqueueJson(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow"}}""")
        assertEquals(LlmErrorKind.QUOTA_OR_RATE_LIMIT, client().stream(simpleRequest).expectLlmError().kind)
    }

    @Test
    fun errorEventInsideStream() = runTest {
        server.enqueueSse(
            messageStart + ev("error", """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""),
        )

        assertEquals(LlmErrorKind.QUOTA_OR_RATE_LIMIT, client().stream(simpleRequest).expectLlmError().kind)
    }

    @Test
    fun truncatedStreamIsBadResponse() = runTest {
        server.enqueueSse(messageStart + blockStart(0, """{"type":"text","text":""}""") + blockDelta(0, """{"type":"text_delta","text":"半"}"""))

        assertEquals(LlmErrorKind.BAD_RESPONSE, client().stream(simpleRequest).expectLlmError().kind)
    }
}
