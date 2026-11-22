package org.xmyun.starmate.core.llm.openai

import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.xmyun.starmate.core.llm.FakeDiagLog
import org.xmyun.starmate.core.llm.TEST_KEY
import org.xmyun.starmate.core.llm.assertNoKeyLeak
import org.xmyun.starmate.core.llm.enqueueJson
import org.xmyun.starmate.core.llm.http.HttpClientFactory
import org.xmyun.starmate.core.llm.json
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.LlmException

class OpenAiEmbeddingClientTest {

    private val server = MockWebServer()
    private val diag = FakeDiagLog()
    private lateinit var http: HttpClient
    private val batchSizes = mutableListOf<Int>()

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

    private fun client() = OpenAiEmbeddingClient(server.url("/v1").toString(), TEST_KEY, "embed-test", http, diag)

    /** 每条输入 "tN" 的向量是 [N]，并且故意倒序返回、只靠 index 标顺序。 */
    private fun reversingDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val inputs = request.json()["input"]!!.jsonArray.map { it.jsonPrimitive.content }
            synchronized(batchSizes) { batchSizes += inputs.size }
            val data = inputs.mapIndexed { i, text ->
                buildJsonObject {
                    put("object", "embedding")
                    put("index", i)
                    putJsonArray("embedding") { add(text.removePrefix("t").toFloat()) }
                }
            }.reversed()
            val body = buildJsonObject { put("data", JsonArray(data)) }.toString()
            return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
        }
    }

    @Test
    fun seventyInputsSplitIntoTwoRequestsAndKeepOrder() = runTest {
        server.dispatcher = reversingDispatcher()
        val texts = (0 until 70).map { "t$it" }

        val vectors = client().embed(texts)

        assertEquals(2, server.requestCount)
        assertEquals(listOf(64, 6), batchSizes)
        assertEquals(70, vectors.size)
        vectors.forEachIndexed { i, v -> assertEquals(i.toFloat(), v[0], 0f) }

        val first = server.takeRequest()
        assertEquals("/v1/embeddings", first.url.encodedPath)
        assertEquals("Bearer $TEST_KEY", first.headers["Authorization"])
        assertEquals("embed-test", first.json()["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun emptyInputMakesNoRequest() = runTest {
        assertEquals(0, client().embed(emptyList()).size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun errorsAreMappedWithoutKey() = runTest {
        server.enqueueJson(401, """{"error":{"message":"bad key $TEST_KEY"}}""")
        try {
            client().embed(listOf("a"))
            fail("应当失败")
        } catch (e: LlmException) {
            assertEquals(LlmErrorKind.INVALID_KEY, e.kind)
            assertNoKeyLeak(e, diag)
        }
    }

    @Test
    fun countMismatchIsBadResponse() = runTest {
        server.enqueueJson(200, """{"data":[{"index":0,"embedding":[1.0]}]}""")
        try {
            client().embed(listOf("a", "b"))
            fail("应当失败")
        } catch (e: LlmException) {
            assertEquals(LlmErrorKind.BAD_RESPONSE, e.kind)
        }
    }
}
