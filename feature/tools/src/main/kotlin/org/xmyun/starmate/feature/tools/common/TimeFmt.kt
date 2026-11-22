package org.xmyun.starmate.feature.tools.common

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** 本地时间的简短写法，一律带星期。 */
class TimeFmt(private val clock: Clock, private val chinese: Boolean) {

    private val zone: ZoneId get() = clock.zone
    private val locale: Locale get() = if (chinese) Locale.CHINESE else Locale.ENGLISH

    private fun today(): LocalDate = LocalDate.now(clock)

    /** “11月14日 周五”；不是今年时带年份。 */
    fun date(date: LocalDate): String {
        val pattern = when {
            chinese && date.year == today().year -> "M月d日 EEE"
            chinese -> "yyyy年M月d日 EEE"
            date.year == today().year -> "EEE, MMM d"
            else -> "EEE, MMM d, yyyy"
        }
        return date.format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    fun time(instant: Instant): String =
        instant.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))

    /** “11月14日 周五 15:00”。 */
    fun dateTime(instant: Instant): String {
        val local = instant.atZone(zone)
        return date(local.toLocalDate()) + " " + time(instant)
    }

    /** 给用户看的近期时间：今天 15:00 / 明天 15:00 / 周五 15:00 / 11月20日 周四 15:00。 */
    fun friendly(instant: Instant): String {
        val local = instant.atZone(zone)
        val days = Duration.between(today().atStartOfDay(zone), local.toLocalDate().atStartOfDay(zone)).toDays()
        val time = time(instant)
        return when (days) {
            0L -> if (chinese) "今天 $time" else "today $time"
            1L -> if (chinese) "明天 $time" else "tomorrow $time"
            in 2L..6L -> local.format(DateTimeFormatter.ofPattern("EEE", locale)) + " " + time
            else -> dateTime(instant)
        }
    }

    /** 多久以前：刚刚 / 12 分钟前 / 3 小时前 / 2 天前。 */
    fun ago(instant: Instant): String {
        val minutes = Duration.between(instant, clock.instant()).toMinutes().coerceAtLeast(0)
        return when {
            minutes < 2 -> if (chinese) "刚刚" else "just now"
            minutes < 60 -> if (chinese) "$minutes 分钟前" else "$minutes min ago"
            minutes < 48 * 60 -> if (chinese) "${minutes / 60} 小时前" else "${minutes / 60} h ago"
            else -> if (chinese) "${minutes / 1440} 天前" else "${minutes / 1440} days ago"
        }
    }

    /** 时长：7小时30分 / 7 h 30 min。 */
    fun duration(d: Duration): String {
        val h = d.toHours()
        val m = d.toMinutes() % 60
        return if (chinese) {
            if (h > 0) "${h}小时${m}分" else "${m}分钟"
        } else {
            if (h > 0) "$h h $m min" else "$m min"
        }
    }
}

/** 解析模型给的本地时间。 */
object TimeParse {

    data class Parsed(val dateTime: LocalDateTime, val dateOnly: Boolean)

    /**
     * 接受 2026-11-14T15:00[:00]、2026-11-14 15:00、带时区偏移的 ISO 时间（换算成本地），
     * 以及只有日期的 2026-11-14（当天 00:00，dateOnly = true）。
     */
    fun parse(text: String?, zone: ZoneId): Parsed? {
        val s = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = s.replace(' ', 'T')
        try {
            return Parsed(LocalDateTime.parse(normalized), false)
        } catch (_: DateTimeParseException) {
        }
        try {
            return Parsed(OffsetDateTime.parse(normalized).atZoneSameInstant(zone).toLocalDateTime(), false)
        } catch (_: DateTimeParseException) {
        }
        try {
            return Parsed(ZonedDateTime.parse(normalized).withZoneSameInstant(zone).toLocalDateTime(), false)
        } catch (_: DateTimeParseException) {
        }
        try {
            return Parsed(LocalDate.parse(s).atStartOfDay(), true)
        } catch (_: DateTimeParseException) {
        }
        return null
    }

    fun parseDate(text: String?): LocalDate? {
        val s = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDate.parse(s.take(10))
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
