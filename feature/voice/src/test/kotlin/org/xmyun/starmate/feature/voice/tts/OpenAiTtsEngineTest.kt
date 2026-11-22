package org.xmyun.starmate.feature.voice.tts

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xmyun.starmate.core.model.EndpointConfig
import org.xmyun.starmate.core.model.EndpointExtras

class OpenAiTtsEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val audio = byteArrayOf(0x49, 0x44, 0x33, 1, 2, 3)
    private val requests = mutableListOf<HttpRequestData>()

    private fun config(extras: Map<String, String> = mapOf(EndpointExtras.VOICE to "nova")) =
        EndpointConfig(baseUrl = "https://api.example.com/v1/", apiKey = "sk-secret-key", model = "gpt-4o-mini-tts", extras = extras)

    private fun engine(
        config: EndpointConfig = config(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
            respond(audio, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "audio/mpeg"))
        },
    ): OpenAiTtsEngine {
        val mock = MockEngine { request ->
            requests += request
            handler(this, request)
        }
        return OpenAiTtsEngine(HttpClient(mock) { expectSuccess = false }, config)
    }

    private fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    private fun target() = File(tmp.root, "out/v1.mp3")

    @Test
    fun requestHasExpectedFields() = runTest {
        val target = target()
        val result = engine().synthesize("晚安呀", null, target)

        assertEquals(TtsResult.Success, result)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://api.example.com/v1/audio/speech", request.url.toString())
        assertEquals("Bearer sk-secret-key", request.headers[HttpHeaders.Authorization])
        val body = bodyOf(request)
        assertEquals("gpt-4o-mini-tts", body["model"]!!.jsonPrimitive.content)
        assertEquals("晚安呀", body["input"]!!.jsonPrimitive.content)
        assertEquals("nova", body["voice"]!!.jsonPrimitive.content)
        assertEquals("mp3", body["response_format"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("instructions"))
        assertArrayEquals(audio, target.readBytes())
    }

    @Test
    fun toneAddsInstructions() = runTest {
        engine().synthesize("晚安", "温柔", target())
        assertEquals("Tone: 温柔", bodyOf(requests.single())["instructions"]!!.jsonPrimitive.content)
    }

    @Test
    fun configuredInstructionsAndToneAreJoined() = runTest {
        val config = config(mapOf(EndpointExtras.INSTRUCTIONS to "Speak like a gentle young woman.", EndpointExtras.FORMAT to "wav"))
        val e = engine(config)
        assertEquals("wav", e.format)
        e.synthesize("嗯", "sleepy", target())
        val body = bodyOf(requests.single())
        assertEquals("Speak like a gentle young woman.\nTone: sleepy", body["instructions"]!!.jsonPrimitive.content)
        assertEquals("wav", body["response_format"]!!.jsonPrimitive.content)
        assertEquals(OpenAiTtsEngine.DEFAULT_VOICE, body["voice"]!!.jsonPrimitive.content)
    }

    @Test
    fun configuredInstructionsAloneAreSent() = runTest {
        engine(config(mapOf(EndpointExtras.INSTRUCTIONS to "Warm."))).synthesize("嗯", null, target())
        assertEquals("Warm.", bodyOf(requests.single())["instructions"]!!.jsonPrimitive.content)
    }

    @Test
    fun httpErrorFailsAndLeavesNoFile() = runTest {
        val target = target()
        val result = engine { respond("""{"error":{"message":"bad key sk-secret-key"}}""", HttpStatusCode.Unauthorized) }
            .synthesize("嗯", null, target)
        assertTrue(result is TtsResult.Failure)
        assertFalse(target.exists())
        assertFalse((result as TtsResult.Failure).reason.contains("sk-secret"))
    }

    @Test
    fun badRequestWithInstructionsRetriesWithout() = runTest {
        val target = target()
        val result = engine { request ->
            if (bodyOf(request).containsKey("instructions")) {
                respond("unsupported", HttpStatusCode.BadRequest)
            } else {
                respond(audio, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "audio/mpeg"))
            }
        }.synthesize("嗯", "温柔", target)
        assertEquals(TtsResult.Success, result)
        assertEquals(2, requests.size)
        assertFalse(bodyOf(requests[1]).containsKey("instructions"))
        assertTrue(target.length() > 0)
    }

    @Test
    fun copyStopsAtTheSizeLimit() {
        val small = java.io.ByteArrayOutputStream()
        assertTrue(OpenAiTtsEngine.copyCapped(ByteArray(1000).inputStream(), small, limit = 1000))
        assertEquals(1000, small.size())

        // 一个永远读不完的流：到上限就停，不会一直写下去
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        }
        val capped = java.io.ByteArrayOutputStream()
        assertFalse(OpenAiTtsEngine.copyCapped(endless, capped, limit = 50_000))
        assertTrue(capped.size() <= 50_000)
    }

    @Test
    fun badRequestWithoutInstructionsDoesNotRetry() = runTest {
        val result = engine { respond("bad", HttpStatusCode.BadRequest) }.synthesize("嗯", null, target())
        assertTrue(result is TtsResult.Failure)
        assertEquals(1, requests.size)
    }

    @Test
    fun jsonBodyWithOkStatusIsFailure() = runTest {
        val target = target()
        val result = engine {
            respond("""{"error":"quota"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }.synthesize("嗯", null, target)
        assertTrue(result is TtsResult.Failure)
        assertFalse(target.exists())
    }

    @Test
    fun emptyBodyIsFailure() = runTest {
        val target = target()
        val result = engine { respond(ByteArray(0), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "audio/mpeg")) }
            .synthesize("嗯", null, target)
        assertTrue(result is TtsResult.Failure)
        assertFalse(target.exists())
    }

    @Test
    fun networkExceptionIsFailureWithoutKey() = runTest {
        val target = target()
        val result = engine { throw IOException("connect to https://api.example.com failed, key sk-secret-key") }
            .synthesize("嗯", null, target)
        assertTrue(result is TtsResult.Failure)
        assertFalse((result as TtsResult.Failure).reason.contains("sk-secret"))
        assertFalse(target.exists())
    }

    @Test
    fun unsupportedFormatFallsBackToMp3() {
        assertEquals("mp3", OpenAiTtsEngine.normalizeFormat("pcm"))
        assertEquals("mp3", OpenAiTtsEngine.normalizeFormat(null))
        assertEquals("opus", OpenAiTtsEngine.normalizeFormat(" OPUS "))
    }

    @Test
    fun endpointToStringHidesKey() {
        assertFalse(config().toString().contains("sk-secret"))
    }
}
