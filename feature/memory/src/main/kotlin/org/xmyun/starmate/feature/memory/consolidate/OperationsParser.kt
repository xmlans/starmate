package org.xmyun.starmate.feature.memory.consolidate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** 解析模型输出的 JSON，并做结构校验。 */
object OperationsParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val FENCE = Regex("```(?:json)?")
    private val KINDS = setOf("add", "update", "merge", "invalidate", "archive", "delete")

    /** 容忍 ```json 包裹和前后多余文字。解析不了返回 null。 */
    fun parseObject(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        val cleaned = FENCE.replace(text, "")
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            json.parseToJsonElement(cleaned.substring(start, end + 1)) as? JsonObject
        } catch (e: Exception) {
            null
        }
    }

    /** 拒绝整批结构错误的结果，不能把错误响应当作合法的空操作。 */
    fun validOperations(data: JsonObject?): Boolean {
        if (data == null) return false
        val ops = data["ops"] as? JsonArray ?: return false
        val gist = data["gist"]
        if (gist != null && gist !is JsonNull && !(gist is JsonPrimitive && gist.isString)) return false
        for (op in ops) {
            if (op !is JsonObject) return false
            val kind = op.str("op") ?: return false
            if (kind !in KINDS) return false
            if (kind == "add" || kind == "merge" || "content" in op) {
                val content = op["content"]
                if (content !is JsonPrimitive || !content.isString || content.content.isBlank()) return false
            }
            if (kind in setOf("update", "invalidate", "archive", "delete")) {
                val id = op.id("id") ?: return false
                if (id <= 0) return false
            }
            if (kind == "merge") {
                val ids = op["ids"] as? JsonArray ?: return false
                if (ids.size < 2 || ids.any { (asId(it) ?: 0) <= 0 }) return false
            }
            for (field in listOf("related", "evidence")) {
                val value = op[field] ?: continue
                if (value !is JsonArray || value.any { (asId(it) ?: 0) <= 0 }) return false
            }
            if ("importance" in op) {
                val p = op["importance"] as? JsonPrimitive ?: return false
                if (p.booleanOrNull != null && !p.isString) return false
                val v = p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() ?: return false
                if (v < 1 || v > 5) return false
            }
        }
        return true
    }

    fun asId(value: JsonElement?): Long? {
        val p = value as? JsonPrimitive ?: return null
        if (!p.isString && p.booleanOrNull != null) return null
        return p.contentOrNull?.trim()?.removePrefix("#")?.toLongOrNull()
            ?: p.doubleOrNull?.takeIf { it == Math.floor(it) }?.toLong()
    }
}

fun JsonObject.str(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.contentOrNull
}

fun JsonObject.id(key: String): Long? = OperationsParser.asId(this[key])

fun JsonObject.int(key: String): Int? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    if (!p.isString && p.booleanOrNull != null) return null
    return p.doubleOrNull?.toInt() ?: p.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()
}

fun JsonObject.ids(key: String): List<Long> =
    (this[key] as? JsonArray)?.mapNotNull { OperationsParser.asId(it) } ?: emptyList()

/** tags 可能是字符串或字符串数组。 */
fun JsonObject.text(key: String): String? = when (val v = this[key]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(" ")
    is JsonPrimitive -> if (v is JsonNull) null else v.contentOrNull
    else -> null
}
