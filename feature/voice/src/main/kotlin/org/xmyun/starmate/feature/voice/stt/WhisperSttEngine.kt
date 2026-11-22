package org.xmyun.starmate.feature.voice.stt

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.xmyun.starmate.core.model.EndpointConfig

/**
 * 录音后上传到 Whisper 兼容的 `POST {baseUrl}/audio/transcriptions`（multipart：file、model），读响应里的 text。
 */
class WhisperSttEngine(
    private val http: HttpClient,
    private val config: EndpointConfig,
    private val recorder: Recorder,
    private val workDir: File,
) : SttEngine {

    private var file: File? = null

    override suspend fun start(onEnded: () -> Unit): Boolean {
        val target = File(workDir, "input-${System.nanoTime()}.m4a")
        val ok = withContext(Dispatchers.Main) { recorder.start(target, MAX_DURATION_MS, onEnded) }
        file = if (ok) target else null
        return ok
    }

    override suspend fun finish(): SttResult {
        val recorded = file ?: return SttResult.Failure(SttFailure.RECORDER)
        file = null
        return try {
            val ok = withContext(Dispatchers.Main) { recorder.stop() }
            if (!ok) SttResult.Failure(SttFailure.RECORDER) else transcribe(recorded)
        } finally {
            recorded.delete()
        }
    }

    override fun cancel() {
        recorder.cancel()
        file?.delete()
        file = null
    }

    suspend fun transcribe(audio: File): SttResult {
        val url = config.baseUrl.trimEnd('/') + "/audio/transcriptions"
        return try {
            val bytes = withContext(Dispatchers.IO) { audio.readBytes() }
            val response = http.submitFormWithBinaryData(
                url = url,
                formData = formData {
                    append(
                        "file",
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, "audio/mp4")
                            append(HttpHeaders.ContentDisposition, "filename=\"${audio.name}\"")
                        },
                    )
                    append("model", config.model)
                },
            ) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            }
            if (!response.status.isSuccess()) {
                return SttResult.Failure(SttFailure.SERVICE)
            }
            val text = parseText(response.bodyAsText())
            when {
                text == null -> SttResult.Failure(SttFailure.SERVICE)
                text.isBlank() -> SttResult.Failure(SttFailure.NO_SPEECH)
                else -> SttResult.Text(text.trim())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SttResult.Failure(SttFailure.NETWORK)
        }
    }

    companion object {
        const val MAX_DURATION_MS = 60_000

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 默认的 json 响应是 {"text": "..."}；有的服务即便要 json 也直接回纯文本，这里一并接受。 */
        fun parseText(body: String): String? {
            val trimmed = body.trim()
            if (trimmed.startsWith("{")) {
                return try {
                    (json.parseToJsonElement(trimmed) as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.contentOrNull }
                } catch (e: Exception) {
                    null
                }
            }
            return trimmed.takeIf { it.isNotEmpty() && !trimmed.startsWith("<") }
        }
    }
}
