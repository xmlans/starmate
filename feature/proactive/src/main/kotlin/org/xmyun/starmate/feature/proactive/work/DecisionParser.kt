package org.xmyun.starmate.feature.proactive.work

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

sealed interface Decision {
    data class Send(val text: String) : Decision

    /** 模型明确决定不发。 */
    data object Skip : Decision

    /** 输出无法解析，按不发处理。 */
    data object Invalid : Decision
}

/** 解析 {"send": true, "text": "…"} 或 {"send": false}。纯函数。 */
object DecisionParser {
    private val json = Json { isLenient = true }

    fun parse(raw: String): Decision {
        val obj = extractObject(raw) ?: return Decision.Invalid
        val send = (obj["send"] as? JsonPrimitive)?.booleanOrNull ?: return Decision.Invalid
        if (!send) return Decision.Skip
        val text = (obj["text"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
        return if (text.isNullOrEmpty()) Decision.Invalid else Decision.Send(text)
    }

    // 容忍代码块围栏和前后多余文字：取第一个 { 到最后一个 }
    private fun extractObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
        } catch (e: Exception) {
            null
        }
    }
}
