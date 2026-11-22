package org.xmyun.starmate.feature.tools.common

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** 读取模型给的参数。模型有时把数字写成字符串，这里都兼容。 */
object JsonArgs {

    fun JsonObject.string(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun JsonObject.int(key: String): Int? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        value.intOrNull?.let { return it }
        val text = value.contentOrNull?.trim() ?: return null
        text.toIntOrNull()?.let { return it }
        val d = value.doubleOrNull ?: text.toDoubleOrNull() ?: return null
        return if (d == Math.floor(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
    }

    /** 缺省时为 null；有值但不全是整数时返回空列表外加 invalid = true。 */
    fun JsonObject.intList(key: String): IntListArg {
        val value = this[key] ?: return IntListArg(null, false)
        if (value is JsonNull) return IntListArg(null, false)
        val array = value as? JsonArray ?: return IntListArg(null, true)
        val ints = array.map { element ->
            val p = element as? JsonPrimitive ?: return IntListArg(null, true)
            p.intOrNull ?: p.contentOrNull?.trim()?.toIntOrNull() ?: return IntListArg(null, true)
        }
        return IntListArg(ints, false)
    }

    data class IntListArg(val values: List<Int>?, val invalid: Boolean)
}

/** 构造 JSON Schema。 */
object Schema {
    fun obj(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties.toMap()))
            if (required.isNotEmpty()) {
                put("required", buildJsonArray { required.forEach { add(it) } })
            }
        }

    fun string(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    fun integer(description: String, minimum: Int? = null, maximum: Int? = null): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
        minimum?.let { put("minimum", it) }
        maximum?.let { put("maximum", it) }
    }

    fun intArray(description: String, minimum: Int, maximum: Int): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject {
            put("type", "integer")
            put("minimum", minimum)
            put("maximum", maximum)
        })
    }
}
