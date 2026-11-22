package org.xmyun.starmate.core.llm.anthropic

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程内记住“baseUrl + 模型名”降档后可用的 max_tokens。客户端每次新建，所以由单例持有。
 * 只在调用方没传 maxOutputTokens、服务端报 max_tokens 超限时使用。
 */
@Singleton
class AnthropicMaxTokensMemory @Inject constructor() {

    private val values = ConcurrentHashMap<String, Int>()

    fun get(key: String): Int? = values[key]

    fun put(key: String, maxTokens: Int) {
        values[key] = maxTokens
    }

    companion object {
        /** 默认值超限后依次尝试的档位。 */
        val LADDER = listOf(32_000, 16_000, 8_192, 4_096)

        fun key(baseUrl: String, model: String): String = baseUrl.trimEnd('/') + "|" + model

        private val HINTS = listOf(
            "exceed", "maximum", "greater than", "too large", "less than", "at most", "up to", "allowed", "range",
            ">", "<=",
        )

        /** 400 的错误体是否在说 max_tokens 超限。 */
        fun isMaxTokensError(body: String): Boolean {
            val lower = body.lowercase()
            return "max_tokens" in lower && HINTS.any { it in lower }
        }

        // 例：max_tokens: 64000 > 32000, which is the maximum allowed number of output tokens for ...
        private val GREATER = Regex("""max_tokens\D{0,20}?(\d+)\s*>\s*(\d+)""", RegexOption.IGNORE_CASE)

        // 例：max_tokens must be less than or equal to 8192 / at most 8192 / range [1, 8192]
        private val BOUND = Regex(
            """(?:less than or equal to|<=|at most|up to|maximum(?: value)?(?: is| of)?)\s*`?(\d+)|\[\s*1\s*,\s*(\d+)\s*]""",
            RegexOption.IGNORE_CASE,
        )

        /** 从错误信息里解析模型允许的上限；解析不出返回 null。 */
        fun parseLimit(body: String): Int? {
            GREATER.find(body)?.let { return it.groupValues[2].toIntOrNull() }
            val m = BOUND.find(body) ?: return null
            return (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
        }
    }
}
