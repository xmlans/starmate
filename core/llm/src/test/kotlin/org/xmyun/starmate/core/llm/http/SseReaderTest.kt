package org.xmyun.starmate.core.llm.http

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class SseReaderTest {

    private suspend fun read(text: String) = SseReader.events(ByteReadChannel(text)).toList()

    @Test
    fun multiLineDataJoinedWithNewline() = runTest {
        val events = read("event: msg\ndata: 第一行\ndata: 第二行\n\n")
        assertEquals(listOf(SseEvent("msg", "第一行\n第二行")), events)
    }

    @Test
    fun commentLinesIgnored() = runTest {
        val events = read(": keep-alive\n\n: OPENROUTER PROCESSING\ndata: a\n: 中间的注释\n\ndata: b\n\n")
        assertEquals(listOf(SseEvent(null, "a"), SseEvent(null, "b")), events)
    }

    @Test
    fun lastEventWithoutTrailingBlankLineIsEmitted() = runTest {
        val events = read("data: one\n\ndata: two")
        assertEquals(listOf(SseEvent(null, "one"), SseEvent(null, "two")), events)
    }

    @Test
    fun dataWithoutSpaceAndCrlf() = runTest {
        val events = read("event:ping\r\ndata:{\"x\":1}\r\n\r\ndata:  two spaces\r\n\r\n")
        assertEquals(listOf(SseEvent("ping", "{\"x\":1}"), SseEvent(null, " two spaces")), events)
    }

    @Test
    fun eventNameResetsBetweenEvents() = runTest {
        val events = read("event: a\ndata: 1\n\ndata: 2\n\nevent: only-name\n\n")
        assertEquals(listOf(SseEvent("a", "1"), SseEvent(null, "2")), events)
    }

    @Test
    fun chunkBoundaryInsideLineAndInsideUtf8() = runTest {
        val channel = ByteChannel()
        val full = "data: {\"t\":\"你好\"}\n\ndata: [DONE]\n\n"
        val bytes = full.toByteArray()
        launch {
            // 每 3 个字节写一次，必然切断中文字符和行
            bytes.toList().chunked(3).forEach { piece ->
                channel.writeFully(piece.toByteArray())
                channel.flush()
                yield()
            }
            channel.flushAndClose()
        }
        val events = SseReader.events(channel).toList()
        assertEquals(listOf(SseEvent(null, "{\"t\":\"你好\"}"), SseEvent(null, "[DONE]")), events)
    }

    @Test
    fun parserFeedDirectly() {
        val parser = SseParser()
        assertEquals(null, parser.feed("data: x"))
        assertEquals(SseEvent(null, "x"), parser.feed(""))
        assertEquals(null, parser.finish())
    }

    @Test
    fun splitDataPayloadsHandlesMissingBlankLines() {
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), splitDataPayloads("{\"a\":1}\n{\"b\":2}"))
        assertEquals(listOf("[DONE]"), splitDataPayloads("[DONE]"))
    }
}
