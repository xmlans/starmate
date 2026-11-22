package org.xmyun.starmate.feature.mobileuse.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.xmyun.starmate.feature.mobileuse.model.ActionKind
import org.xmyun.starmate.feature.mobileuse.model.AgentAction
import org.xmyun.starmate.feature.mobileuse.model.ScrollDirection

sealed interface ParseResult {
    data class Ok(val action: AgentAction) : ParseResult

    /** message 是发回给模型的说明（英文，简短）。 */
    data class Error(val message: String) : ParseResult
}

/**
 * 把模型的一步输出解析成 [AgentAction]。只认一个 JSON 对象，例如
 * `{"action": "tap", "index": 3, "why": "…"}`。容忍外面包了 ``` 代码块或前后有少量文字。
 *
 * @param lookAvailable 模型不支持图片时 look 视为未知动作。
 */
class ActionParser(private val lookAvailable: Boolean) {

    fun parse(raw: String): ParseResult {
        val obj = extractObject(raw) ?: return ParseResult.Error("Reply with exactly one JSON object and nothing else.")
        val name = obj.string("action")?.trim()?.lowercase()
            ?: return ParseResult.Error("Missing \"action\".")
        val kind = ActionKind.fromWire(name)
        if (kind == null || (kind == ActionKind.LOOK && !lookAvailable)) {
            return ParseResult.Error("Unknown action \"${name.take(40)}\". Allowed: ${allowedNames()}.")
        }
        val why = obj.string("why")?.take(MAX_WHY).orEmpty()
        return when (kind) {
            ActionKind.TAP -> withIndex(obj) { AgentAction.Tap(it, why) }
            ActionKind.LONG_PRESS -> withIndex(obj) { AgentAction.LongPress(it, why) }
            ActionKind.INPUT_TEXT -> withIndex(obj) { index ->
                val text = obj.string("text") ?: return ParseResult.Error("input_text needs \"text\".")
                if (text.length > MAX_INPUT) return ParseResult.Error("\"text\" is too long (max $MAX_INPUT characters).")
                AgentAction.InputText(index, text, why)
            }
            ActionKind.SCROLL -> withIndex(obj) { index ->
                val direction = parseDirection(obj.string("direction"))
                    ?: return ParseResult.Error("scroll needs \"direction\": up, down, left or right.")
                AgentAction.Scroll(index, direction, why)
            }
            ActionKind.BACK -> ParseResult.Ok(AgentAction.Back(why))
            ActionKind.HOME -> ParseResult.Ok(AgentAction.Home(why))
            ActionKind.OPEN_APP -> {
                val pkg = obj.string("package")?.trim()
                if (pkg.isNullOrEmpty()) return ParseResult.Error("open_app needs \"package\".")
                ParseResult.Ok(AgentAction.OpenApp(pkg, why))
            }
            ActionKind.WAIT -> {
                val seconds = obj.number("seconds") ?: DEFAULT_WAIT.toDouble()
                ParseResult.Ok(AgentAction.Wait(seconds.toInt().coerceIn(1, MAX_WAIT_SECONDS), why))
            }
            ActionKind.LOOK -> ParseResult.Ok(AgentAction.Look(why))
            ActionKind.ASK_USER -> {
                val question = obj.string("question")?.trim()
                if (question.isNullOrEmpty()) return ParseResult.Error("ask_user needs \"question\".")
                ParseResult.Ok(AgentAction.AskUser(question.take(MAX_QUESTION), why))
            }
            ActionKind.DONE -> {
                val summary = obj.string("summary")?.trim()
                if (summary.isNullOrEmpty()) return ParseResult.Error("done needs \"summary\".")
                ParseResult.Ok(AgentAction.Done(summary.take(MAX_SUMMARY), why))
            }
        }
    }

    private inline fun withIndex(obj: JsonObject, build: (Int) -> AgentAction): ParseResult {
        val index = obj.number("index")
            ?: return ParseResult.Error("This action needs \"index\" (the number in brackets).")
        if (index < 0 || index != Math.floor(index) || index > Int.MAX_VALUE) {
            return ParseResult.Error("\"index\" must be one of the numbers in brackets.")
        }
        return ParseResult.Ok(build(index.toInt()))
    }

    private fun allowedNames(): String =
        ActionKind.entries.filter { it != ActionKind.LOOK || lookAvailable }.joinToString(", ") { it.wireName }

    private fun parseDirection(s: String?): ScrollDirection? = when (s?.trim()?.lowercase()) {
        "up", "backward" -> ScrollDirection.UP
        "down", "forward" -> ScrollDirection.DOWN
        "left" -> ScrollDirection.LEFT
        "right" -> ScrollDirection.RIGHT
        else -> null
    }

    private fun extractObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
        } catch (e: Exception) {
            null
        }
    }

    private fun JsonObject.string(key: String): String? {
        val element: JsonElement = this[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.number(key: String): Double? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.longOrNull?.toDouble() ?: p.doubleOrNull ?: p.contentOrNull?.trim()?.toDoubleOrNull()
    }

    companion object {
        const val MAX_WAIT_SECONDS = 5
        const val DEFAULT_WAIT = 2
        const val MAX_INPUT = 500
        const val MAX_WHY = 200
        const val MAX_QUESTION = 200
        const val MAX_SUMMARY = 1000
    }
}
