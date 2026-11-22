package org.xmyun.starmate.core.llm.openai

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.xmyun.starmate.core.llm.http.ErrorMapper
import org.xmyun.starmate.core.llm.http.LlmJson
import org.xmyun.starmate.core.llm.http.errorBody
import org.xmyun.starmate.core.llm.http.hostOf
import org.xmyun.starmate.core.llm.http.http
import org.xmyun.starmate.core.llm.http.joinUrl
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.EmbeddingClient
import org.xmyun.starmate.core.model.LlmException

/** OpenAI 兼容 `POST /embeddings`。一次最多 [BATCH_SIZE] 条，超出自动分批，结果顺序与输入一致。 */
class OpenAiEmbeddingClient(
    private val baseUrl: String,
    private val apiKey: String,
    override val modelId: String,
    private val http: HttpClient,
    private val diag: DiagLog,
) : EmbeddingClient {

    private val url = joinUrl(baseUrl, "/embeddings")
    private val secrets = listOf(apiKey)

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return texts.chunked(BATCH_SIZE).flatMap { embedBatch(it) }
    }

    private suspend fun embedBatch(batch: List<String>): List<FloatArray> {
        try {
            val body = buildJsonObject {
                put("model", modelId)
                putJsonArray("input") { batch.forEach { add(it) } }
            }
            val response = http.post(url) {
                if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
            val status = response.status.value
            diag.http("POST", url, status)
            if (!response.status.isSuccess()) throw ErrorMapper.fromStatus(status, response.errorBody(), secrets)
            val parsed = try {
                LlmJson.decodeFromString(EmbeddingResponse.serializer(), response.bodyAsText())
            } catch (e: Exception) {
                throw ErrorMapper.badResponse("embedding 响应无法解析", e)
            }
            if (parsed.data.size != batch.size) {
                throw ErrorMapper.badResponse("embedding 数量 ${parsed.data.size} 与输入 ${batch.size} 不一致")
            }
            return parsed.data
                .withIndex()
                .sortedBy { (position, item) -> item.index ?: position }
                .map { it.value.embedding.toFloatArray() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            throw e
        } catch (e: Throwable) {
            throw ErrorMapper.fromThrowable(e)
        }
    }

    override fun toString(): String = "OpenAiEmbeddingClient(host=${hostOf(url)}, model=$modelId)"

    companion object {
        const val BATCH_SIZE = 64
    }
}
