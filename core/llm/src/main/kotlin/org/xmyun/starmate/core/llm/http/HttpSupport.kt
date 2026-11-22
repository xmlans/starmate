package org.xmyun.starmate.core.llm.http

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.xmyun.starmate.core.model.DiagLog

/** 宽松解析：各家服务商的字段多少不一，缺字段、多字段、null 都不应报错。 */
val LlmJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

internal const val DIAG_TAG = "llm"

/** `baseUrl` 去掉结尾的 `/` 后原样拼接。 */
internal fun joinUrl(baseUrl: String, path: String): String = baseUrl.trimEnd('/') + path

internal fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: "?"

/** 只记方法、主机和状态码。 */
internal fun DiagLog.http(method: String, url: String, status: Int) {
    log(DIAG_TAG, "$method ${hostOf(url)} $status")
}

/**
 * 读出错响应的正文，最多读 [MAX_ERROR_BODY_BYTES]。不用 bodyAsText：那会把整个响应读进内存，
 * 出了故障的接口可能回一个很大甚至不结束的响应。读不出来时返回空串。
 */
internal suspend fun HttpResponse.errorBody(): String = try {
    val bytes = withContext(Dispatchers.IO) {
        bodyAsChannel().toInputStream().use { input -> readCapped(input, MAX_ERROR_BODY_BYTES) }
    }
    bytes.toString(Charsets.UTF_8).take(8_000)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ""
}

internal const val MAX_ERROR_BODY_BYTES = 64 * 1024

/** 从流里最多读 [limit] 字节，多出来的不读。 */
internal fun readCapped(input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

internal fun HttpResponse.isEventStream(): Boolean =
    contentType()?.match(ContentType.Text.EventStream) == true

internal fun HttpResponse.isJson(): Boolean =
    contentType()?.match(ContentType.Application.Json) == true

/**
 * 冷流外壳：在生产者里发请求、读流，网络和解析异常统一转成 LlmException，取消照常传播。
 * 用 channelFlow 而不是 flow，是为了在 Ktor 的 execute 块里发事件时不受协程上下文约束。
 */
internal fun <T> llmFlow(block: suspend ProducerScope<T>.() -> Unit): Flow<T> = channelFlow {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw ErrorMapper.fromThrowable(e)
    }
}

/** 把一段 data 解析成 JsonObject；解析不了时返回 null。 */
internal fun parseObjectOrNull(data: String): JsonObject? =
    runCatching { LlmJson.parseToJsonElement(data).jsonObject }.getOrNull()

/**
 * 有的中转站连续发多行 `data:` 却不空行分隔，SSE 规范会把它们拼成一个事件。
 * 整体解析失败时按行拆开再逐个解析。
 */
internal fun splitDataPayloads(data: String): List<String> {
    if ('\n' !in data) return listOf(data)
    if (parseObjectOrNull(data) != null) return listOf(data)
    return data.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
}
