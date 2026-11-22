package org.xmyun.starmate.core.llm.http

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 一个 SSE 事件。[event] 为 null 表示服务端没写 `event:`。 */
data class SseEvent(val event: String?, val data: String)

/**
 * 按行累积 SSE 字段：`event:`、`data:` 累积，空行结束一个事件，以 `:` 开头的是注释。
 * 多行 `data:` 用 `\n` 连接；冒号后的一个空格可有可无。
 */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** 喂一行（不含换行符）。一个事件结束时返回它。 */
    fun feed(rawLine: String): SseEvent? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            // id、retry 等字段用不到
        }
        return null
    }

    /** 流结束时调用：结尾没有空行的最后一个事件也要交出去。 */
    fun finish(): SseEvent? = flush()

    private fun flush(): SseEvent? {
        val result = if (hasData) SseEvent(event, data.toString()) else null
        event = null
        data.setLength(0)
        hasData = false
        return result
    }
}

object SseReader {
    /** 把响应体按行解析成事件流。分块边界落在一行中间时由 readLine 负责拼好。 */
    fun events(channel: ByteReadChannel): Flow<SseEvent> = flow {
        val parser = SseParser()
        while (true) {
            val line = channel.readLine() ?: break
            parser.feed(line)?.let { emit(it) }
        }
        parser.finish()?.let { emit(it) }
    }
}
