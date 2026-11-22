package org.xmyun.starmate.feature.memory.retrieval

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.xmyun.starmate.feature.memory.NOW
import org.xmyun.starmate.feature.memory.ZONE

class TimeExpressionParserTest {
    private fun days(text: String): Pair<LocalDate, LocalDate>? =
        TimeExpressionParser.parse(text, NOW, ZONE)?.let { MemoryTime.localDate(it.start, ZONE) to MemoryTime.localDate(it.end, ZONE) }

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test
    fun relativeDays() {
        assertEquals(d(2026, 10, 29) to d(2026, 10, 29), days("今天面试好紧张"))
        assertEquals(d(2026, 10, 28) to d(2026, 10, 28), days("昨天"))
        assertEquals(d(2026, 10, 27) to d(2026, 10, 27), days("前天晚上"))
        assertEquals(d(2026, 10, 26) to d(2026, 10, 26), days("大前天"))
        assertEquals(d(2026, 10, 23) to d(2026, 10, 25), days("5天前"))
        assertEquals(d(2026, 10, 25) to d(2026, 10, 27), days("三天前"))
    }

    @Test
    fun weeksMonthsYears() {
        assertEquals(d(2026, 10, 19) to d(2026, 10, 25), days("上周我跟你说的那件事"))
        val thisWeek = TimeExpressionParser.parse("这周", NOW, ZONE)!!
        assertEquals(d(2026, 10, 26), MemoryTime.localDate(thisWeek.start, ZONE))
        assertEquals(NOW, thisWeek.end)
        assertEquals(d(2026, 9, 1) to d(2026, 9, 30), days("上个月"))
        assertEquals(d(2026, 7, 1) to d(2026, 7, 31), days("三个月前"))
        assertEquals(d(2026, 8, 1) to d(2026, 8, 31), days("2个月前"))
        assertEquals(d(2025, 1, 1) to d(2025, 12, 31), days("去年"))
        assertEquals(d(2024, 1, 1) to d(2024, 12, 31), days("前年"))
        assertEquals(d(2026, 1, 1) to d(2026, 10, 29), days("今年"))
    }

    @Test
    fun concreteDates() {
        assertEquals(d(2026, 5, 3) to d(2026, 5, 3), days("2026-05-03"))
        assertEquals(d(2026, 5, 3) to d(2026, 5, 3), days("2026年5月3日那天"))
        // 未来的日期视为去年
        assertEquals(d(2025, 12, 25) to d(2025, 12, 25), days("12月25日"))
        assertEquals(d(2026, 10, 1) to d(2026, 10, 1), days("10月1号"))
        assertEquals(d(2025, 3, 1) to d(2025, 3, 31), days("2025年3月"))
    }

    @Test
    fun invalidOrUnknown() {
        assertNull(days("你好呀"))
        assertNull(days("2026年13月"))
        assertNull(days("2026-02-30"))
        assertNull(days(""))
    }

    @Test
    fun englishExpressions() {
        assertEquals(d(2026, 10, 28) to d(2026, 10, 28), days("what did I say yesterday"))
        assertEquals(d(2026, 10, 19) to d(2026, 10, 25), days("last week"))
        assertEquals(d(2026, 9, 1) to d(2026, 9, 30), days("last month"))
        assertEquals(d(2026, 10, 23) to d(2026, 10, 25), days("5 days ago"))
    }

    @Test
    fun chineseNumbers() {
        assertEquals(3, TimeExpressionParser.cnNumber("三"))
        assertEquals(12, TimeExpressionParser.cnNumber("十二"))
        assertEquals(20, TimeExpressionParser.cnNumber("二十"))
        assertEquals(23, TimeExpressionParser.cnNumber("二十三"))
        assertEquals(0, TimeExpressionParser.cnNumber("半"))
        assertNull(TimeExpressionParser.cnNumber("几"))
    }

    @Test
    fun parseDateFromModel() {
        assertEquals("2026-05-01", MemoryTime.fmtDate(MemoryTime.parseDate("2026-05", ZONE), ZONE))
        assertEquals("2026-05-03", MemoryTime.fmtDate(MemoryTime.parseDate("2026/5/3", ZONE), ZONE))
        assertNull(MemoryTime.parseDate("明天", ZONE))
        assertNull(MemoryTime.parseDate("2026-13-01", ZONE))
    }
}
