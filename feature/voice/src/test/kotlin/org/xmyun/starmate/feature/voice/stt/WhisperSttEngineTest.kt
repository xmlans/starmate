package org.xmyun.starmate.feature.voice.stt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xmyun.starmate.core.model.EndpointConfig

class WhisperSttEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()

    private val config = EndpointConfig(baseUrl = "https://stt.example.com/v1", apiKey = "sk-stt-secret", model = "whisper-1")

    private object NoopRecorder : Recorder {
        override fun start(target: File, maxDurationMs: Int, onMaxDuration: () -> Unit) = false
        override fun stop() = false
        override fun cancel() = Unit
    }

    private fun engine(status: HttpStatusCode = HttpStatusCode.OK, body: String = """{"text":"你好呀"}""", type: String = "application/json") =
        WhisperSttEngine(
            HttpClient(
                MockEngine { request ->
                    requests += request
                    bodies += String(request.body.toByteArray(), Charsets.UTF_8)
                    respond(body, status, headersOf(HttpHeaders.ContentType, type))
                },
            ) { expectSuccess = false },
            config,
            NoopRecorder,
            tmp.root,
        )

    private fun audioFile() = tmp.newFile("input-1.m4a").apply { writeBytes(byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70)) }

    @Test
    fun multipartHasFileAndModel() = runTest {
        val result = engine().transcribe(audioFile())

        assertEquals(SttResult.Text("你好呀"), result)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://stt.example.com/v1/audio/transcriptions", request.url.toString())
        assertEquals("Bearer sk-stt-secret", request.headers[HttpHeaders.Authorization])
        assertTrue(request.body.contentType.toString().startsWith("multipart/form-data"))

        val body = bodies.single()
        assertTrue(body, body.contains("name=file") || body.contains("name=\"file\""))
        assertTrue(body, body.contains("filename=\"input-1.m4a\""))
        assertTrue(body, body.contains("name=model") || body.contains("name=\"model\""))
        assertTrue(body, body.contains("whisper-1"))
    }

    @Test
    fun parsesTextField() = runTest {
        assertEquals(SttResult.Text("晚安"), engine(body = """{"text":" 晚安 ","language":"zh","duration":1.2}""").transcribe(audioFile()))
    }

    @Test
    fun emptyTextIsNoSpeech() = runTest {
        assertEquals(SttResult.Failure(SttFailure.NO_SPEECH), engine(body = """{"text":""}""").transcribe(audioFile()))
    }

    @Test
    fun httpErrorIsServiceFailure() = runTest {
        assertEquals(
            SttResult.Failure(SttFailure.SERVICE),
            engine(status = HttpStatusCode.Unauthorized, body = """{"error":"bad key"}""").transcribe(audioFile()),
        )
    }

    @Test
    fun plainTextResponseAccepted() {
        assertEquals("你好", WhisperSttEngine.parseText("你好\n"))
        assertNull(WhisperSttEngine.parseText("""{"error":"x"}"""))
        assertNull(WhisperSttEngine.parseText("<html>oops</html>"))
        assertNull(WhisperSttEngine.parseText("{not json"))
    }
}
