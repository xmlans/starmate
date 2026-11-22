package org.xmyun.starmate.core.llm.openai

import io.ktor.client.HttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.xmyun.starmate.core.llm.FakeDiagLog
import org.xmyun.starmate.core.llm.TEST_KEY
import org.xmyun.starmate.core.llm.assertNoKeyLeak
import org.xmyun.starmate.core.llm.enqueueJson
import org.xmyun.starmate.core.llm.enqueueSse
import org.xmyun.starmate.core.llm.expectLlmError
import org.xmyun.starmate.core.llm.http.HttpClientFactory
import org.xmyun.starmate.core.llm.http.LlmJson
import org.xmyun.starmate.core.llm.json
import org.xmyun.starmate.core.llm.sse
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

class OpenAiCompatClientTest {

    private val server = MockWebServer()
    private val diag = FakeDiagLog()
    private lateinit var http: HttpClient

    private val simpleRequest = LlmRequest(
        system = SystemPrompt("你是星伴"),
        messages = listOf(LlmMessage(Role.USER, "在吗")),
    )

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

    // 结尾带 /，验证会被去掉
    private fun client(httpClient: HttpClient = http, baseUrl: String = server.url("/v1/").toString()) =
        OpenAiCompatClient(baseUrl, TEST_KEY, "gpt-test", supportsTools = true, supportsImages = true, httpClient, diag)

    private fun delta(content: String) = """{"choices":[{"index":0,"delta":{"content":"$content"}}]}"""
    private fun finish(reason: String) = """{"choices":[{"index":0,"delta":{},"finish_reason":"$reason"}]}"""

    private val longReply = "我猜……是自己想做的小东西？那种反正是自己的、怎么折腾都不亏的项目。"

    private fun texts(events: List<LlmEvent>) = events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }

    @Test
    fun finalChunkCarryingTheWholeMessageIsNotAppendedAgain() = runTest {
        // 有的中转站在增量发完后补一块只带 message 的整段回复
        server.enqueueSse(
            sse(
                delta(longReply.substring(0, 10)),
                delta(longReply.substring(10)),
                """{"choices":[{"index":0,"message":{"role":"assistant","content":"$longReply"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )

        val events = client().stream(simpleRequest).toList()

        assertEquals(longReply, texts(events))
        assertTrue(diag.entries.value.any { "又发了一遍" in it.message })
    }

    @Test
    fun finalDeltaRepeatingTheWholeReplyIsNotAppendedAgain() = runTest {
        server.enqueueSse(sse(delta(longReply.substring(0, 10)), delta(longReply.substring(10)), delta(longReply), finish("stop"), "[DONE]"))

        assertEquals(longReply, texts(client().stream(simpleRequest).toList()))
    }

    @Test
    fun messageOnlyChunkStillWorksForServersThatSendJustThat() = runTest {
        server.enqueueSse(
            sse("""{"choices":[{"index":0,"message":{"role":"assistant","content":"$longReply"},"finish_reason":"stop"}]}""", "[DONE]"),
        )

        assertEquals(longReply, texts(client().stream(simpleRequest).toList()))
    }

    @Test
    fun threeTextChunksThenDone() = runTest {
        server.enqueueSse(sse(delta("你"), delta("好"), delta("呀"), finish("stop"), "[DONE]"))

        val events = client().stream(simpleRequest).toList()

        assertEquals(
            listOf(
                LlmEvent.TextDelta("你"),
                LlmEvent.TextDelta("好"),
                LlmEvent.TextDelta("呀"),
                // 不返回 usage 的服务商：按 0 处理
                LlmEvent.Completed(TokenUsage(), StopReason.END),
            ),
            events,
        )
        assertEquals("/v1/chat/completions", server.takeRequest().url.encodedPath)
    }

    @Test
    fun noSpaceAfterDataAndTinyChunks() = runTest {
        val body = listOf(delta("流式"), delta("分块"), finish("length"), "[DONE]")
            .joinToString("") { "data:$it\n\n" }
        server.enqueueSse(body, maxChunkSize = 5)

        val events = client().stream(simpleRequest).toList()

        assertEquals(
            listOf(
                LlmEvent.TextDelta("流式"),
                LlmEvent.TextDelta("分块"),
                LlmEvent.Completed(TokenUsage(), StopReason.MAX_TOKENS),
            ),
            events,
        )
    }

    @Test
    fun toolCallArgumentsInThreeChunks() = runTest {
        server.enqueueSse(
            sse(
                """{"choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"上海\","}}]}}]}""",
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"unit\":\"c\"}"}}]}}]}""",
                finish("tool_calls"),
                "[DONE]",
            ),
        )

        val events = client().stream(simpleRequest).toList()

        val expectedArgs = buildJsonObject {
            put("city", "上海")
            put("unit", "c")
        }
        assertEquals(
            listOf(
                LlmEvent.ToolCallRequested(ToolCall("call_1", "get_weather", expectedArgs)),
                LlmEvent.Completed(TokenUsage(), StopReason.TOOL_CALLS),
            ),
            events,
        )
    }

    @Test
    fun brokenToolArgumentsBecomeEmptyObjectAndAreLogged() = runTest {
        server.enqueueSse(
            sse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"t","arguments":"{\"a\":"}}]}}]}""",
                finish("tool_calls"),
                "[DONE]",
            ),
        )

        val events = client().stream(simpleRequest).toList()

        assertEquals(LlmEvent.ToolCallRequested(ToolCall("c1", "t", JsonObject(emptyMap()))), events[0])
        assertTrue(diag.lines.any { "参数" in it })
    }

    @Test
    fun usageInLastChunkIncludesCachedTokens() = runTest {
        server.enqueueSse(
            sse(
                delta("嗯"),
                finish("stop"),
                """{"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150,"prompt_tokens_details":{"cached_tokens":100}}}""",
                "[DONE]",
            ),
        )

        val events = client().stream(simpleRequest).toList()

        assertEquals(LlmEvent.Completed(TokenUsage(120, 30, 100), StopReason.END), events.last())
    }

    @Test
    fun deepSeekCacheHitField() = runTest {
        server.enqueueSse(
            sse(
                delta("嗯"),
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":50,"completion_tokens":5,"prompt_cache_hit_tokens":40,"prompt_tokens_details":null}}""",
                "[DONE]",
            ),
        )

        val events = client().stream(simpleRequest).toList()

        assertEquals(LlmEvent.Completed(TokenUsage(50, 5, 40), StopReason.END), events.last())
    }

    @Test
    fun requestBodyShape() = runTest {
        server.enqueueSse(sse(finish("stop"), "[DONE]"))
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }
        val request = LlmRequest(
            system = SystemPrompt(stable = "稳定部分", volatile = "此刻 21:00"),
            messages = listOf(
                LlmMessage(Role.USER, "看看这张图", images = listOf(ImagePart("image/png", "AAAA"))),
                LlmMessage(
                    Role.ASSISTANT,
                    "",
                    toolCalls = listOf(ToolCall("call_9", "calendar_list_events", buildJsonObject { put("day", "today") })),
                ),
                LlmMessage(Role.TOOL, "没有日程", toolCallId = "call_9"),
            ),
            tools = listOf(ToolSpec("calendar_list_events", "列出日程", schema)),
            maxOutputTokens = 100,
            temperature = 0.5,
            jsonOutput = true,
        )

        client().stream(request).toList()

        val recorded = server.takeRequest()
        assertEquals("Bearer $TEST_KEY", recorded.headers["Authorization"])
        val body = recorded.json()
        assertEquals("gpt-test", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertTrue(body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(100, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("0.5", body["temperature"]!!.jsonPrimitive.content)

        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("稳定部分\n\n此刻 21:00", messages[0].jsonObject["content"]!!.jsonPrimitive.content)

        val userContent = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals("text", userContent[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", userContent[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/png;base64,AAAA",
            userContent[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )

        val assistant = messages[2].jsonObject
        val call = assistant["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("call_9", call["id"]!!.jsonPrimitive.content)
        assertEquals("function", call["type"]!!.jsonPrimitive.content)
        val args = LlmJson.parseToJsonElement(call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals("today", args.jsonObject["day"]!!.jsonPrimitive.content)

        val tool = messages[3].jsonObject
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("call_9", tool["tool_call_id"]!!.jsonPrimitive.content)

        val toolDef = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", toolDef["type"]!!.jsonPrimitive.content)
        assertEquals("calendar_list_events", toolDef["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun optionalFieldsOmittedWhenNull() = runTest {
        server.enqueueSse(sse(finish("stop"), "[DONE]"))

        client().stream(LlmRequest(SystemPrompt(""), listOf(LlmMessage(Role.USER, "hi")))).toList()

        val body = server.takeRequest().json()
        assertNull(body["max_tokens"])
        assertNull(body["temperature"])
        assertNull(body["response_format"])
        assertNull(body["tools"])
        // system 为空时不发 system 消息
        assertEquals("user", body["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolHistoryFlattenedWhenNoToolsGiven() = runTest {
        server.enqueueSse(sse(finish("stop"), "[DONE]"))
        val request = LlmRequest(
            system = SystemPrompt("s"),
            messages = listOf(
                LlmMessage(Role.USER, "明天有什么安排"),
                LlmMessage(
                    Role.ASSISTANT,
                    "我看看",
                    toolCalls = listOf(ToolCall("call_1", "calendar_list_events", buildJsonObject { put("day", "tomorrow") })),
                ),
                LlmMessage(Role.TOOL, "下午三点开会", toolCallId = "call_1"),
                LlmMessage(Role.USER, "好的"),
            ),
            tools = emptyList(),
        )

        client().stream(request).toList()

        val body = server.takeRequest().json()
        val raw = body.toString()
        assertFalse(raw.contains("tool_calls"))
        assertFalse(raw.contains("tool_call_id"))
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertTrue(messages.none { it["role"]!!.jsonPrimitive.content == "tool" })
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertTrue(messages[2]["content"]!!.jsonPrimitive.content.contains("calendar_list_events"))
        // 工具结果变成用户侧文字，并和随后的用户消息合并
        val lastUser = messages[3]["content"]!!.jsonPrimitive.content
        assertTrue(lastUser.contains("calendar_list_events"))
        assertTrue(lastUser.contains("下午三点开会"))
        assertTrue(lastUser.endsWith("\n\n好的"))
    }

    @Test
    fun consecutiveUserMessagesMerged() = runTest {
        server.enqueueSse(sse(finish("stop"), "[DONE]"))
        val request = LlmRequest(
            SystemPrompt(""),
            listOf(LlmMessage(Role.ASSISTANT, "早呀"), LlmMessage(Role.USER, "在吗"), LlmMessage(Role.USER, "人呢")),
        )

        client().stream(request).toList()

        val messages = server.takeRequest().json()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("在吗\n\n人呢", messages[1]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun streamOptionsRejectedThenRetriedWithout() = runTest {
        server.enqueueJson(400, """{"error":{"message":"Unrecognized request argument supplied: stream_options"}}""")
        server.enqueueSse(sse(delta("好"), finish("stop"), "[DONE]"))

        val events = client().stream(simpleRequest).toList()

        assertEquals(LlmEvent.TextDelta("好"), events.first())
        assertTrue(server.takeRequest().json().containsKey("stream_options"))
        assertFalse(server.takeRequest().json().containsKey("stream_options"))
    }

    @Test
    fun nonStreamingJsonResponseIsAccepted() = runTest {
        server.enqueueJson(
            200,
            """{"choices":[{"index":0,"message":{"role":"assistant","content":"整段回复"},"finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":3}}""",
        )

        val events = client().stream(simpleRequest).toList()

        assertEquals(
            listOf(LlmEvent.TextDelta("整段回复"), LlmEvent.Completed(TokenUsage(9, 3, 0), StopReason.END)),
            events,
        )
    }

    @Test
    fun dataLinesWithoutBlankSeparators() = runTest {
        server.enqueueSse("data: ${delta("一")}\ndata: ${delta("二")}\n\ndata: [DONE]\n\n")

        val events = client().stream(simpleRequest).toList()

        assertEquals(listOf(LlmEvent.TextDelta("一"), LlmEvent.TextDelta("二")), events.dropLast(1))
    }

    @Test
    fun streamEndsWithoutDoneStillCompletes() = runTest {
        server.enqueueSse(sse(delta("a"), finish("stop")))

        val events = client().stream(simpleRequest).toList()

        assertEquals(LlmEvent.Completed(TokenUsage(), StopReason.END), events.last())
    }

    @Test
    fun errorObjectInsideStream() = runTest {
        server.enqueueSse(sse(delta("a"), """{"error":{"message":"Rate limit reached","type":"rate_limit_error"}}"""))

        val e = client().stream(simpleRequest).expectLlmError()

        assertEquals(LlmErrorKind.QUOTA_OR_RATE_LIMIT, e.kind)
    }

    @Test
    fun garbageBodyIsBadResponse() = runTest {
        server.enqueueSse("data: <html>oops</html>\n\n")

        assertEquals(LlmErrorKind.BAD_RESPONSE, client().stream(simpleRequest).expectLlmError().kind)
    }

    @Test
    fun statusCodesMapToKinds() = runTest {
        val cases = listOf(
            Triple(401, """{"error":{"message":"invalid key"}}""", LlmErrorKind.INVALID_KEY),
            Triple(403, """{"error":{"message":"forbidden"}}""", LlmErrorKind.INVALID_KEY),
            Triple(429, """{"error":{"message":"slow down"}}""", LlmErrorKind.QUOTA_OR_RATE_LIMIT),
            Triple(402, """{"error":{"message":"Insufficient Balance"}}""", LlmErrorKind.QUOTA_OR_RATE_LIMIT),
            Triple(404, """{"error":{"message":"The model `x` does not exist"}}""", LlmErrorKind.MODEL_NOT_FOUND),
            Triple(400, """{"error":{"message":"tools are not supported"}}""", LlmErrorKind.TOOLS_UNSUPPORTED),
            Triple(400, """{"error":{"message":"bad things"}}""", LlmErrorKind.UNKNOWN),
            Triple(500, "oops", LlmErrorKind.UNKNOWN),
        )
        for ((code, body, kind) in cases) {
            server.enqueueJson(code, body)
            val e = client().stream(simpleRequest).expectLlmError()
            assertEquals("HTTP $code", kind, e.kind)
            assertEquals(code, e.httpStatus)
        }
    }

    @Test
    fun keyNeverLeaksIntoErrorsOrDiagLog() = runTest {
        server.enqueueJson(401, """{"error":{"message":"Incorrect API key provided: $TEST_KEY. Header was Bearer $TEST_KEY"}}""")

        val c = client()
        val e = c.stream(simpleRequest).expectLlmError()

        assertEquals(LlmErrorKind.INVALID_KEY, e.kind)
        assertNoKeyLeak(e, diag)
        assertFalse(c.toString().contains(TEST_KEY))
        assertTrue("诊断日志应当记录了请求", diag.lines.any { "POST" in it && "401" in it })
    }

    @Test
    fun unreachableHost() = runTest {
        val dead = MockWebServer()
        dead.start()
        val url = dead.url("/v1").toString()
        dead.close()

        val e = client(baseUrl = url).stream(simpleRequest).expectLlmError()

        assertEquals(LlmErrorKind.UNREACHABLE, e.kind)
        assertNoKeyLeak(e, diag)
    }

    @Test
    fun slowServerTimesOut() = runTest {
        val shortHttp = HttpClientFactory.create(connectTimeoutMs = 2_000, socketTimeoutMs = 300)
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .headersDelay(3, TimeUnit.SECONDS)
                    .body(sse("[DONE]"))
                    .build(),
            )

            val e = client(httpClient = shortHttp).stream(simpleRequest).expectLlmError()

            assertEquals(LlmErrorKind.TIMEOUT, e.kind)
        } finally {
            shortHttp.close()
        }
    }

    @Test
    fun noHttpLoggingLibraryOnClasspath() {
        // 模块不依赖任何 HTTP 日志插件，请求头里的 key 不可能被它们打印出来
        for (name in listOf("io.ktor.client.plugins.logging.Logging", "okhttp3.logging.HttpLoggingInterceptor")) {
            try {
                Class.forName(name)
                fail("$name 不应在 classpath 上")
            } catch (expected: ClassNotFoundException) {
                // 预期如此
            }
        }
    }
}
