package org.xmyun.starmate.core.llm

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.xmyun.starmate.core.llm.http.LlmJson
import org.xmyun.starmate.core.model.DiagEntry
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.LlmEvent
import org.xmyun.starmate.core.model.LlmException
import org.xmyun.starmate.core.model.SecretStore

const val TEST_KEY = "sk-test-SECRET-9f8e7d6c5b4a3210"

class FakeDiagLog : DiagLog {
    private val state = MutableStateFlow<List<DiagEntry>>(emptyList())
    override val entries: StateFlow<List<DiagEntry>> = state
    val lines: List<String> get() = state.value.map { "${it.tag}: ${it.message}" }

    override fun log(tag: String, message: String) {
        state.value = state.value + DiagEntry(Instant.now(), tag, message)
    }
}

class FakeSecretStore : SecretStore {
    val values = mutableMapOf<String, String>()
    override suspend fun get(name: String): String? = values[name]
    override suspend fun set(name: String, value: String?) {
        if (value == null) values.remove(name) else values[name] = value
    }
}

/** 每个 data 一个事件，`data: ` 后带空格。 */
fun sse(vararg data: String): String = data.joinToString("") { "data: $it\n\n" }

/** 带 event 名的 SSE 事件。 */
fun ev(name: String, data: String): String = "event: $name\ndata: $data\n\n"

fun MockWebServer.enqueueSse(body: String, maxChunkSize: Int = Int.MAX_VALUE) {
    enqueue(
        MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "text/event-stream")
            .chunkedBody(body, maxChunkSize)
            .build(),
    )
}

fun MockWebServer.enqueueJson(code: Int, body: String) {
    enqueue(
        MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build(),
    )
}

fun RecordedRequest.json(): JsonObject = LlmJson.parseToJsonElement(body!!.utf8()).jsonObject

suspend fun Flow<LlmEvent>.expectLlmError(): LlmException {
    try {
        toList()
    } catch (e: LlmException) {
        return e
    }
    fail("应当抛出 LlmException")
    throw AssertionError()
}

/** 断言 key 不出现在异常（含 cause 链）和诊断日志里。 */
fun assertNoKeyLeak(e: Throwable?, diag: FakeDiagLog, key: String = TEST_KEY) {
    var t: Throwable? = e
    while (t != null) {
        assertFalse("异常消息里有 key: ${t.javaClass}", t.message.orEmpty().contains(key))
        assertFalse("异常 toString 里有 key", t.toString().contains(key))
        t = t.cause
    }
    diag.lines.forEach { assertFalse("诊断日志里有 key: $it", it.contains(key)) }
}
