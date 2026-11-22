package org.xmyun.starmate.core.data.diag

/** 给日志和诊断信息打码：保留前 4 位，其余换成 `***`。 */
object Redactor {

    private const val MASK = "***"

    private val bearer = Regex("""(?i)(\bBearer\s+)([A-Za-z0-9._~+/=-]+)""")

    private val skKey = Regex("""sk-[A-Za-z0-9_-]{8,}""")

    private val queryParam = Regex("""(?i)([?&](?:key|api_key|apikey|tavilyApiKey|token|access_token)=)([^&#\s"']+)""")

    private val apiKeyHeader = Regex("""(?i)(\bx-(?:goog-)?api-key\s*[:=]\s*)([^\s,;"']+)""")

    // JSON 里同名字段也打码，例如请求体里的 "api_key":"..."
    private val jsonField = Regex(
        """(?i)("(?:key|api_key|apikey|tavilyApiKey|token|x-api-key|access_token)"\s*:\s*")([^"]+)(")""",
    )

    fun redact(text: String): String {
        var out = text
        out = bearer.replace(out) { it.groupValues[1] + mask(it.groupValues[2]) }
        out = queryParam.replace(out) { it.groupValues[1] + mask(it.groupValues[2]) }
        out = apiKeyHeader.replace(out) { it.groupValues[1] + mask(it.groupValues[2]) }
        out = jsonField.replace(out) { it.groupValues[1] + mask(it.groupValues[2]) + it.groupValues[3] }
        out = skKey.replace(out) { mask(it.value) }
        return out
    }

    /** 已打过码的值保持不变；4 位及以下的值整个换掉。 */
    fun mask(value: String): String = when {
        value.endsWith(MASK) && value.length <= 4 + MASK.length -> value
        value.length <= 4 -> MASK
        else -> value.take(4) + MASK
    }
}
