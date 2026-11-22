package org.xmyun.starmate.feature.chat.interview

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 初识访谈得到的资料。字段与设计文档 5.1 一致，全部可空，没问到的留空。
 * - proactivity：often / sometimes / never
 * - sleepTime、wakeTime：HH:mm（24 小时制）
 */
@Serializable
data class OnboardingProfile(
    val nickname: String? = null,
    val birthday: String? = null,
    val sleepTime: String? = null,
    val wakeTime: String? = null,
    val occupation: String? = null,
    val likes: List<String>? = null,
    val dislikes: List<String>? = null,
    val personaPreference: String? = null,
    val proactivity: String? = null,
    val avoidTopics: List<String>? = null,
    val otherFacts: List<String>? = null,
) {
    fun isEmpty(): Boolean =
        nickname == null && birthday == null && sleepTime == null && wakeTime == null &&
            occupation == null && likes.isNullOrEmpty() && dislikes.isNullOrEmpty() &&
            personaPreference == null && proactivity == null &&
            avoidTopics.isNullOrEmpty() && otherFacts.isNullOrEmpty()

    fun toJson(): String = json.encodeToString(serializer(), normalized())

    /** 把空串、空列表收成 null，时间和主动程度规范化。 */
    fun normalized(): OnboardingProfile = OnboardingProfile(
        nickname = nickname.clean(),
        birthday = birthday.clean(),
        sleepTime = normalizeTime(sleepTime),
        wakeTime = normalizeTime(wakeTime),
        occupation = occupation.clean(),
        likes = likes.cleanList(),
        dislikes = dislikes.cleanList(),
        personaPreference = personaPreference.clean(),
        proactivity = normalizeProactivity(proactivity),
        avoidTopics = avoidTopics.cleanList(),
        otherFacts = otherFacts.cleanList(),
    )

    companion object {
        const val OFTEN = "often"
        const val SOMETIMES = "sometimes"
        const val NEVER = "never"

        val EMPTY = OnboardingProfile()

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }

        private val TIME = Regex("""^(\d{1,2})\s*[:：]\s*(\d{2})$""")

        /**
         * 宽松解析模型输出：去掉代码块标记和前后的杂字，接受多余字段、单个字符串当列表、
         * 外面包一层 {"profile": {...}}。不是合法 JSON 时返回空资料。
         */
        fun parse(text: String?): OnboardingProfile {
            if (text.isNullOrBlank()) return EMPTY
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return EMPTY
            val element = try {
                json.parseToJsonElement(text.substring(start, end + 1))
            } catch (e: Exception) {
                return EMPTY
            }
            val obj = element as? JsonObject ?: return EMPTY
            return fromJson(obj)
        }

        fun fromJson(source: JsonObject): OnboardingProfile {
            val obj = (source["profile"] as? JsonObject) ?: source
            return OnboardingProfile(
                nickname = obj.string("nickname"),
                birthday = obj.string("birthday"),
                sleepTime = obj.string("sleepTime"),
                wakeTime = obj.string("wakeTime"),
                occupation = obj.string("occupation"),
                likes = obj.list("likes"),
                dislikes = obj.list("dislikes"),
                personaPreference = obj.string("personaPreference"),
                proactivity = obj.string("proactivity"),
                avoidTopics = obj.list("avoidTopics"),
                otherFacts = obj.list("otherFacts"),
            ).normalized()
        }

        /** "1:00"、"01：00" → "01:00"；不合法返回 null。 */
        fun normalizeTime(value: String?): String? {
            val match = TIME.matchEntire(value?.trim() ?: return null) ?: return null
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            if (hour !in 0..24 || minute !in 0..59) return null
            val h = if (hour == 24) 0 else hour
            return "%02d:%02d".format(h, minute)
        }

        fun normalizeProactivity(value: String?): String? =
            when (value?.trim()?.lowercase()) {
                OFTEN -> OFTEN
                SOMETIMES -> SOMETIMES
                NEVER -> NEVER
                else -> null
            }

        /** HH:mm → 一天中的分钟数。 */
        fun minuteOfDay(time: String?): Int? {
            val normalized = normalizeTime(time) ?: return null
            val (h, m) = normalized.split(':').map { it.toInt() }
            return h * 60 + m
        }

        private fun JsonObject.string(key: String): String? = when (val value = this[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.contentOrNull
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("、")
            else -> null
        }

        private fun JsonObject.list(key: String): List<String>? = when (val value = this[key]) {
            null, JsonNull -> null
            is JsonArray -> value.mapNotNull { element -> element.primitiveText() }
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            else -> null
        }

        private fun JsonElement.primitiveText(): String? = (this as? JsonPrimitive)?.contentOrNull

        private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

        private fun List<String>?.cleanList(): List<String>? =
            this?.mapNotNull { it.clean() }?.distinct()?.takeIf { it.isNotEmpty() }
    }
}
