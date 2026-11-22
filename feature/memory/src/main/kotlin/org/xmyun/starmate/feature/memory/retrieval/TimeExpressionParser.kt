package org.xmyun.starmate.feature.memory.retrieval

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import org.xmyun.starmate.feature.memory.model.DAY_MS
import org.xmyun.starmate.feature.memory.model.Span

/** 时间换算：都按注入的时区，把本地日期和毫秒时间戳互转。 */
object MemoryTime {
    fun localDate(ts: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

    fun localDateTime(ts: Long, zone: ZoneId): LocalDateTime = Instant.ofEpochMilli(ts).atZone(zone).toLocalDateTime()

    fun startOfDay(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    fun fmtDate(ts: Long?, zone: ZoneId, withTime: Boolean = false): String {
        if (ts == null) return ""
        val t = localDateTime(ts, zone)
        val date = "%04d-%02d-%02d".format(t.year, t.monthValue, t.dayOfMonth)
        return if (withTime) "$date %02d:%02d".format(t.hour, t.minute) else date
    }

    /** 模型给的 YYYY-MM-DD / YYYY-MM / YYYY/MM/DD / YYYY 转时间戳；无法表示时返回 null。 */
    fun parseDate(value: String?, zone: ZoneId): Long? {
        val text = (value ?: "").trim().take(10)
        val full = Regex("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})$").find(text)
        val month = Regex("^(\\d{4})-(\\d{1,2})$").find(text)
        val year = Regex("^(\\d{4})$").find(text)
        return try {
            when {
                full != null -> startOfDay(
                    LocalDate.of(full.groupValues[1].toInt(), full.groupValues[2].toInt(), full.groupValues[3].toInt()),
                    zone,
                )
                month != null -> startOfDay(LocalDate.of(month.groupValues[1].toInt(), month.groupValues[2].toInt(), 1), zone)
                year != null -> startOfDay(LocalDate.of(year.groupValues[1].toInt(), 1, 1), zone)
                else -> null
            }
        } catch (e: DateTimeException) {
            null
        }
    }

    /** 相对今天差几天，-2..2 返回 day_-2..day_2 的键，其他返回 null 由调用方处理。 */
    fun dayOffset(ts: Long, now: Long, zone: ZoneId): Long =
        java.time.temporal.ChronoUnit.DAYS.between(localDate(now, zone), localDate(ts, zone))
}

/** 把“昨天”“上周”“三个月前”“5月3日”之类换算成时间范围，用于按时间找回忆。 */
object TimeExpressionParser {
    private val CN_DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    private val ENGLISH_NUMBERS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
    )

    /** 阿拉伯数字或一百以内的中文数字：三、十二、二十、二十三。“半”返回 0。 */
    fun cnNumber(raw: String): Int? {
        val text = raw.trim()
        if (text.isNotEmpty() && text.all { it.isDigit() }) return text.toIntOrNull()
        if (text == "半") return 0
        if ('十' in text) {
            val head = text.substringBefore('十')
            val tail = text.substringAfter('十')
            val tens = if (head.isEmpty()) 1 else if (head.length == 1) CN_DIGITS[head[0]] else null
            val ones = if (tail.isEmpty()) 0 else if (tail.length == 1) CN_DIGITS[tail[0]] else null
            return if (tens == null || ones == null) null else tens * 10 + ones
        }
        return if (text.length == 1) CN_DIGITS[text[0]] else null
    }

    /** 无法识别、日期写错（13 月）时返回 null。 */
    fun parse(text: String?, now: Long, zone: ZoneId): Span? {
        if (text.isNullOrBlank()) return null
        return try {
            parseInner(text, now, zone)
        } catch (e: DateTimeException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    private fun num(s: String): Int {
        val value = cnNumber(s) ?: cnNumber(s.take(1))
        return value ?: ENGLISH_NUMBERS[s.lowercase()] ?: 1
    }

    private fun day(d: LocalDate, zone: ZoneId): Span {
        val start = MemoryTime.startOfDay(d, zone)
        return Span(start, MemoryTime.startOfDay(d.plusDays(1), zone) - 1)
    }

    private fun month(year: Int, month: Int, zone: ZoneId): Span {
        val ym = YearMonth.of(year, 1).plusMonths((month - 1).toLong())
        val start = MemoryTime.startOfDay(ym.atDay(1), zone)
        return Span(start, MemoryTime.startOfDay(ym.plusMonths(1).atDay(1), zone) - 1)
    }

    private fun year(y: Int, zone: ZoneId, now: Long? = null): Span {
        val start = MemoryTime.startOfDay(LocalDate.of(y, 1, 1), zone)
        val end = MemoryTime.startOfDay(LocalDate.of(y + 1, 1, 1), zone) - 1
        return Span(start, if (now == null) end else minOf(now, end))
    }

    private fun parseInner(raw: String, now: Long, zone: ZoneId): Span? {
        val text = raw.lowercase()
        val today = MemoryTime.localDate(now, zone)
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())

        Regex("(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})").find(text)?.let { m ->
            return day(LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()), zone)
        }
        Regex("(\\d{1,2})月(\\d{1,2})[日号]").find(text)?.let { m ->
            var d = LocalDate.of(today.year, m.groupValues[1].toInt(), m.groupValues[2].toInt())
            if (d.isAfter(today)) d = d.withYear(today.year - 1)
            return day(d, zone)
        }
        Regex("(\\d{4})年(\\d{1,2})月").find(text)?.let { m ->
            val mm = m.groupValues[2].toInt()
            if (mm !in 1..12) return null
            return month(m.groupValues[1].toInt(), mm, zone)
        }
        val dayRules = listOf(
            "大前天" to 3L, "前天|day before yesterday" to 2L, "昨天|昨晚|昨日|yesterday|last night" to 1L,
            "今天|今早|今晚|today|tonight|this morning" to 0L,
        )
        for ((pattern, back) in dayRules) {
            if (Regex(pattern).containsMatchIn(text)) return day(today.minusDays(back), zone)
        }
        val daysAgo = Regex("([\\d一两二三四五六七八九十]+)\\s*天(之)?前").find(text)
            ?: Regex("(\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten)\\s+days?\\s+ago").find(text)
        if (daysAgo != null) {
            val d = today.minusDays(num(daysAgo.groupValues[1]).toLong())
            return Span(day(d.minusDays(1), zone).start, day(d.plusDays(1), zone).end)
        }
        if (Regex("上(个)?(周|星期|礼拜)|last week").containsMatchIn(text)) {
            val lastMonday = monday.minusDays(7)
            return Span(day(lastMonday, zone).start, day(lastMonday.plusDays(6), zone).end)
        }
        if (Regex("这(个)?(周|星期|礼拜)|本周|this week").containsMatchIn(text)) {
            return Span(day(monday, zone).start, now)
        }
        val monthsAgo = Regex("([\\d一两二三四五六七八九十半]+)\\s*个?月(之)?前").find(text)
            ?: Regex("(\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten)\\s+months?\\s+ago").find(text)
        if (monthsAgo != null) {
            val months = num(monthsAgo.groupValues[1])
            if (months == 0) return Span(now - 21 * DAY_MS, now - 9 * DAY_MS)
            return month(today.year, today.monthValue - months, zone)
        }
        if (Regex("上(个)?月|last month").containsMatchIn(text)) return month(today.year, today.monthValue - 1, zone)
        if (Regex("这个月|本月|this month").containsMatchIn(text)) return month(today.year, today.monthValue, zone)
        val yearsAgo = Regex("([\\d一两二三四五六七八九十]+)\\s*年(之)?前").find(text)
            ?: Regex("(\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten)\\s+years?\\s+ago").find(text)
        if (yearsAgo != null) return year(today.year - num(yearsAgo.groupValues[1]), zone)
        for ((pattern, delta) in listOf("前年|year before last" to 2, "去年|last year" to 1, "今年|this year" to 0)) {
            if (Regex(pattern).containsMatchIn(text)) return year(today.year - delta, zone, now)
        }
        if (Regex("最近|这几天|这阵子|近来|recently|lately|these days").containsMatchIn(text)) {
            return Span(now - 7 * DAY_MS, now)
        }
        return null
    }
}
