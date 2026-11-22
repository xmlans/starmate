package org.xmyun.starmate.feature.diary.write

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

enum class DiaryFrequency { OFF, SOMETIMES, OFTEN }

/** “当天”以凌晨 4 点为界：过了零点才写的日记仍然算前一天的。 */
object DiaryDay {
    val BOUNDARY: LocalTime = LocalTime.of(4, 0)

    data class Range(val from: Instant, val date: LocalDate)

    /** 从上一个本地 04:00 到 now，以及这篇日记归属的日期（那个 04:00 所在的日期）。 */
    fun range(now: Instant, zone: ZoneId): Range {
        val local = now.atZone(zone)
        val date = if (local.toLocalTime() >= BOUNDARY) local.toLocalDate() else local.toLocalDate().minusDays(1)
        return Range(date.atTime(BOUNDARY).atZone(zone).toInstant(), date)
    }
}

object DiaryDecider {
    const val MIN_TURNS = 6
    const val SOMETIMES_CHANCE = 0.4
    const val OFTEN_CHANCE = 0.8

    /** roll 是调用方传入的 0–1 随机数。 */
    fun shouldAttempt(turnCount: Int, frequency: DiaryFrequency, lastEntryDate: LocalDate?, day: LocalDate, roll: Double): Boolean {
        if (turnCount < MIN_TURNS) return false
        if (lastEntryDate != null && !lastEntryDate.isBefore(day)) return false
        return when (frequency) {
            DiaryFrequency.OFF -> false
            DiaryFrequency.SOMETIMES -> roll < SOMETIMES_CHANCE
            DiaryFrequency.OFTEN -> roll < OFTEN_CHANCE
        }
    }
}

/** 每晚 22:30–01:30 之间随机一个时刻。 */
object DiaryWindow {
    val START: LocalTime = LocalTime.of(22, 30)
    val LENGTH: Duration = Duration.ofHours(3)

    /**
     * 下一个还没开始的窗口里的随机时刻。正处在窗口里时排到下一晚，
     * 这样任务执行完再安排下一次时，同一晚不会跑第二次。
     */
    fun nextRun(now: ZonedDateTime, roll: Double): ZonedDateTime {
        var start = now.toLocalDate().atTime(START).atZone(now.zone)
        if (!start.isAfter(now)) start = start.plusDays(1)
        val offset = (LENGTH.toMinutes() * roll.coerceIn(0.0, 0.9999)).toLong()
        return start.plusMinutes(offset)
    }
}

/** 模型输出：{"write": true, "title", "mood", "body", "summary"} 或 {"write": false}。 */
sealed interface DiaryOutput {
    data class Write(val title: String, val mood: String, val body: String, val summary: String) : DiaryOutput
    data object Skip : DiaryOutput
    data object Invalid : DiaryOutput

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String?): DiaryOutput {
            if (text.isNullOrBlank()) return Invalid
            val cleaned = text.replace(Regex("```(?:json)?"), "")
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start < 0 || end <= start) return Invalid
            val obj = try {
                json.parseToJsonElement(cleaned.substring(start, end + 1)) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return Invalid
            val write = (obj["write"] as? JsonPrimitive)?.booleanOrNull ?: return Invalid
            if (!write) return Skip
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim().orEmpty()
            val body = str("body")
            val title = str("title")
            if (body.isEmpty() || title.isEmpty()) return Invalid
            return Write(title.take(60), str("mood").take(20), body, str("summary").take(200))
        }
    }
}

/** 上一篇日记的最后一段，最多 200 字。 */
fun lastParagraph(body: String): String =
    body.split('\n').map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty().takeLast(200)
