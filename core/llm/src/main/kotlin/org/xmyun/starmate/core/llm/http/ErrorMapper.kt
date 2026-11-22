package org.xmyun.starmate.core.llm.http

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.LlmException

/**
 * 把 HTTP 状态码、网络异常、服务端错误体统一转成 [LlmException]。
 * 所有对外的消息都先经过 [redact]，保证 key 不会出现在异常消息里。
 */
object ErrorMapper {

    private const val MAX_DETAIL = 300

    fun fromStatus(status: Int, body: String, secrets: Collection<String>): LlmException {
        val lower = body.lowercase()
        val toolsUnsupported = (status == 400 || status == 404 || status == 422) &&
            ("tool" in lower || "function" in lower) && "support" in lower
        val kind = when {
            status == 401 || status == 403 -> LlmErrorKind.INVALID_KEY
            status == 402 || status == 429 -> LlmErrorKind.QUOTA_OR_RATE_LIMIT
            toolsUnsupported -> LlmErrorKind.TOOLS_UNSUPPORTED
            status == 404 -> LlmErrorKind.MODEL_NOT_FOUND
            else -> LlmErrorKind.UNKNOWN
        }
        val detail = extractMessage(body) ?: body.take(MAX_DETAIL)
        val message = buildString {
            append("HTTP ").append(status)
            if (detail.isNotBlank()) append(": ").append(redact(detail.take(MAX_DETAIL), secrets))
        }
        return LlmException(kind, message, httpStatus = status)
    }

    /** 流里收到的错误对象（OpenAI 兼容的 `{"error":{...}}`、Anthropic 的 `event: error`）。 */
    fun fromStreamError(error: JsonObject?, secrets: Collection<String>): LlmException {
        val type = (error?.get("type") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val msg = (error?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val tag = "$type $code".lowercase()
        val kind = when {
            "authentication" in tag || "permission" in tag || "invalid_api_key" in tag -> LlmErrorKind.INVALID_KEY
            "rate_limit" in tag || "overloaded" in tag || "quota" in tag || "billing" in tag ->
                LlmErrorKind.QUOTA_OR_RATE_LIMIT
            "not_found" in tag -> LlmErrorKind.MODEL_NOT_FOUND
            else -> LlmErrorKind.UNKNOWN
        }
        val text = msg.ifBlank { type.ifBlank { "stream error" } }
        return LlmException(kind, redact(text.take(MAX_DETAIL), secrets))
    }

    /** 网络层异常。[LlmException] 原样返回；取消异常不应传进来。 */
    fun fromThrowable(t: Throwable): LlmException {
        if (t is LlmException) return t
        return when {
            t.isTimeout() -> LlmException(LlmErrorKind.TIMEOUT, "请求超时", cause = t)
            t.isUnreachable() -> LlmException(LlmErrorKind.UNREACHABLE, "无法连接服务器：${t.javaClass.simpleName}", cause = t)
            t is SerializationException -> badResponse("响应无法解析", t)
            t is IOException -> LlmException(LlmErrorKind.UNREACHABLE, "网络中断：${t.javaClass.simpleName}", cause = t)
            else -> LlmException(LlmErrorKind.UNKNOWN, "未知错误：${t.javaClass.simpleName}", cause = t)
        }
    }

    fun badResponse(detail: String, cause: Throwable? = null): LlmException =
        LlmException(LlmErrorKind.BAD_RESPONSE, "响应格式不对：$detail", cause = cause)

    private fun Throwable.isTimeout(): Boolean = causes().any {
        it is HttpRequestTimeoutException || it is ConnectTimeoutException || it is InterruptedIOException
    }

    private fun Throwable.isUnreachable(): Boolean = causes().any {
        it is UnknownHostException || it is ConnectException || it is NoRouteToHostException ||
            it is UnresolvedAddressException || it is SSLException
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { c -> c.cause?.takeIf { it !== c } }.take(8)

    /** 从常见错误体里取出人能读的那句话。 */
    fun extractMessage(body: String): String? {
        val obj = runCatching { LlmJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val error = obj["error"]
        val fromError = when (error) {
            is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> error.contentOrNull
            else -> null
        }
        return fromError ?: (obj["message"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["detail"] as? JsonPrimitive)?.contentOrNull
    }

    private val KEY_LIKE = Regex("""\b(sk|ak|key|rk)[-_][A-Za-z0-9_\-*.]{8,}""")
    private val BEARER = Regex("""(?i)bearer\s+\S+""")

    /** 去掉文本里的 key：精确匹配传入的密钥，再兜底抹掉形似 key 的串。 */
    fun redact(text: String, secrets: Collection<String>): String {
        var out = text
        for (s in secrets) {
            if (s.length >= 4) out = out.replace(s, "***")
        }
        out = BEARER.replace(out, "Bearer ***")
        out = KEY_LIKE.replace(out) { m -> m.groupValues[1] + "-***" }
        return out
    }
}
