package org.xmyun.starmate.feature.voice.tts

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.xmyun.starmate.core.model.EndpointConfig
import org.xmyun.starmate.core.model.EndpointExtras

/**
 * OpenAI 兼容的 `POST {baseUrl}/audio/speech`。
 *
 * 请求体：model、input、voice、response_format，以及可选的 instructions。
 * instructions 只有部分模型支持（OpenAI 的 gpt-4o-mini-tts 支持，tts-1 不支持），
 * 所以带 instructions 的请求被 400 拒绝时，去掉它重试一次。响应体是音频本身，直接写入文件。
 */
class OpenAiTtsEngine(
    private val http: HttpClient,
    private val config: EndpointConfig,
) : TtsEngine {

    override val model: String get() = config.model

    override val format: String = normalizeFormat(config.extras[EndpointExtras.FORMAT])

    private val voice: String = config.extras[EndpointExtras.VOICE]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_VOICE

    private val url: String = config.baseUrl.trimEnd('/') + "/audio/speech"

    override suspend fun synthesize(text: String, tone: String?, target: File): TtsResult {
        val instructions = buildInstructions(config.extras[EndpointExtras.INSTRUCTIONS], tone)
        val first = request(text, instructions, target)
        if (first is Outcome.Http && first.status == 400 && instructions != null) {
            return request(text, null, target).toResult()
        }
        return first.toResult()
    }

    private sealed interface Outcome {
        data object Ok : Outcome
        data class Http(val status: Int) : Outcome
        data class Error(val reason: String) : Outcome
    }

    private fun Outcome.toResult(): TtsResult = when (this) {
        Outcome.Ok -> TtsResult.Success
        is Outcome.Http -> TtsResult.Failure("HTTP $status")
        is Outcome.Error -> TtsResult.Failure(reason)
    }

    private suspend fun request(text: String, instructions: String?, target: File): Outcome {
        val body = buildJsonObject {
            put("model", config.model)
            put("input", text)
            put("voice", voice)
            put("response_format", format)
            if (instructions != null) put("instructions", instructions)
        }.toString()
        return try {
            val outcome = http.preparePost(url) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                setBody(TextContent(body, ContentType.Application.Json))
            }.execute { response ->
                if (!response.status.isSuccess()) return@execute Outcome.Http(response.status.value)
                // 有的中转站出错时仍返回 200 加一段 JSON 或文本
                val type = response.contentType()
                if (type != null && (type.match(ContentType.Application.Json) || type.contentType == "text")) {
                    return@execute Outcome.Error("unexpected content type ${type.contentType}/${type.contentSubtype}")
                }
                val channel = response.bodyAsChannel()
                val complete = withContext(Dispatchers.IO) {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> channel.toInputStream().use { copyCapped(it, out, MAX_AUDIO_BYTES) } }
                }
                when {
                    // 出了故障的接口可能回一个很大甚至不结束的响应，不能让它写满存储
                    !complete -> Outcome.Error("audio larger than ${MAX_AUDIO_BYTES / (1024 * 1024)} MB")
                    target.length() == 0L -> Outcome.Error("empty body")
                    else -> Outcome.Ok
                }
            }
            if (outcome != Outcome.Ok) target.delete()
            outcome
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: Exception) {
            target.delete()
            // 只记异常类型，异常消息可能带出地址或响应内容
            Outcome.Error(e.javaClass.simpleName)
        }
    }

    companion object {
        const val DEFAULT_VOICE = "alloy"
        const val DEFAULT_FORMAT = "mp3"

        /** 一条语音的大小上限。一条消息的语音正常只有几百 KB，wav 也到不了这个数。 */
        const val MAX_AUDIO_BYTES = 32L * 1024 * 1024

        /** 把 [input] 抄到 [out]，最多 [limit] 字节。抄完返回 true；超出上限时停下并返回 false。 */
        internal fun copyCapped(input: InputStream, out: OutputStream, limit: Long): Boolean {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return true
                total += read
                if (total > limit) return false
                out.write(buffer, 0, read)
            }
        }

        /** OpenAI 支持 mp3、opus、aac、flac、wav、pcm。pcm 没有文件头，播放器认不出，改用 mp3。 */
        private val SUPPORTED_FORMATS = setOf("mp3", "opus", "aac", "flac", "wav")

        fun normalizeFormat(value: String?): String {
            val f = value?.trim()?.lowercase().orEmpty()
            return if (f in SUPPORTED_FORMATS) f else DEFAULT_FORMAT
        }

        /** 配置的语气指令和这段语音的 tone 都有时拼在一起。 */
        fun buildInstructions(configured: String?, tone: String?): String? {
            val parts = listOfNotNull(
                configured?.trim()?.takeIf { it.isNotEmpty() },
                tone?.trim()?.takeIf { it.isNotEmpty() }?.let { "Tone: $it" },
            )
            return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
    }
}
