package org.xmyun.starmate.feature.diary

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.core.model.ATTACHMENT_PREVIEW_KEY
import org.xmyun.starmate.core.model.AttachmentTypes
import org.xmyun.starmate.core.model.QuotePayload
import org.xmyun.starmate.feature.diary.db.DiaryEntryEntity
import org.xmyun.starmate.feature.diary.ui.diaryQuote
import org.xmyun.starmate.feature.diary.write.DiaryDay
import org.xmyun.starmate.feature.diary.write.DiaryDecider
import org.xmyun.starmate.feature.diary.write.DiaryFrequency
import org.xmyun.starmate.feature.diary.write.DiaryOutput
import org.xmyun.starmate.feature.diary.write.DiaryWindow
import org.xmyun.starmate.feature.diary.write.lastParagraph

private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Instant = LocalDateTime.of(y, m, d, h, min).atZone(ZONE).toInstant()
private fun fourAm(y: Int, m: Int, d: Int) = at(y, m, d, 4, 0)

class DiaryDayTest {
    @Test
    fun lateEveningBelongsToSameDay() {
        val r = DiaryDay.range(at(2026, 11, 12, 23, 50), ZONE)
        assertEquals(fourAm(2026, 11, 12), r.from)
        assertEquals(LocalDate.of(2026, 11, 12), r.date)
    }

    @Test
    fun afterMidnightBelongsToPreviousDay() {
        val r = DiaryDay.range(at(2026, 11, 13, 1, 10), ZONE)
        assertEquals(fourAm(2026, 11, 12), r.from)
        assertEquals(LocalDate.of(2026, 11, 12), r.date)
    }

    @Test
    fun boundaries() {
        val before = DiaryDay.range(at(2026, 11, 13, 3, 59), ZONE)
        assertEquals(LocalDate.of(2026, 11, 12), before.date)
        assertEquals(fourAm(2026, 11, 12), before.from)
        val exact = DiaryDay.range(at(2026, 11, 13, 4, 0), ZONE)
        assertEquals(LocalDate.of(2026, 11, 13), exact.date)
        assertEquals(fourAm(2026, 11, 13), exact.from)
    }
}

class DiaryDeciderTest {
    private val day = LocalDate.of(2026, 11, 12)
    private val yesterday = day.minusDays(1)

    @Test
    fun rules() {
        assertFalse(DiaryDecider.shouldAttempt(5, DiaryFrequency.OFTEN, null, day, 0.0))
        assertTrue(DiaryDecider.shouldAttempt(6, DiaryFrequency.SOMETIMES, yesterday, day, 0.39))
        assertFalse(DiaryDecider.shouldAttempt(6, DiaryFrequency.SOMETIMES, yesterday, day, 0.41))
        assertTrue(DiaryDecider.shouldAttempt(6, DiaryFrequency.OFTEN, null, day, 0.79))
        assertFalse(DiaryDecider.shouldAttempt(6, DiaryFrequency.OFTEN, null, day, 0.81))
        assertFalse(DiaryDecider.shouldAttempt(100, DiaryFrequency.OFF, null, day, 0.0))
        assertFalse(DiaryDecider.shouldAttempt(100, DiaryFrequency.OFTEN, day, day, 0.0))
    }
}

class DiarySchedulerTest {
    private fun inWindow(t: LocalDateTime): Boolean {
        val time = t.toLocalTime()
        return time >= LocalTime.of(22, 30) || time <= LocalTime.of(1, 30)
    }

    @Test
    fun nextRunFallsInTonightsWindow() {
        val now = LocalDateTime.of(2026, 11, 12, 15, 0).atZone(ZONE)
        for (roll in listOf(0.0, 0.25, 0.5, 0.99)) {
            val next = DiaryWindow.nextRun(now, roll)
            assertTrue(next.isAfter(now))
            assertTrue(inWindow(next.toLocalDateTime()))
            assertTrue(next.toLocalDate() == now.toLocalDate() || next.toLocalDate() == now.toLocalDate().plusDays(1))
        }
        assertEquals(LocalDateTime.of(2026, 11, 13, 0, 0), DiaryWindow.nextRun(now, 0.5).toLocalDateTime())
    }

    @Test
    fun passedOrRunningWindowMovesToNextNight() {
        val inside = LocalDateTime.of(2026, 11, 12, 23, 10).atZone(ZONE)
        assertEquals(LocalDateTime.of(2026, 11, 13, 22, 30), DiaryWindow.nextRun(inside, 0.0).toLocalDateTime())
        val earlyMorning = LocalDateTime.of(2026, 11, 13, 2, 0).atZone(ZONE)
        val next = DiaryWindow.nextRun(earlyMorning, 0.9)
        assertEquals(LocalDateTime.of(2026, 11, 14, 1, 12), next.toLocalDateTime())
        assertTrue(inWindow(next.toLocalDateTime()))
    }
}

class DiaryOutputTest {
    @Test
    fun parsesWriteSkipAndInvalid() {
        val ok = DiaryOutput.parse("```json\n{\"write\": true, \"title\": \"雨天\", \"mood\": \"安静\", \"body\": \"今天下雨了。\", \"summary\": \"我记下了雨天\"}\n```")
        assertEquals(DiaryOutput.Write("雨天", "安静", "今天下雨了。", "我记下了雨天"), ok)
        assertEquals(DiaryOutput.Skip, DiaryOutput.parse("{\"write\": false}"))
        assertEquals(DiaryOutput.Invalid, DiaryOutput.parse("不是 JSON"))
        assertEquals(DiaryOutput.Invalid, DiaryOutput.parse("{\"write\": true, \"title\": \"\", \"body\": \"x\"}"))
        assertEquals(DiaryOutput.Invalid, DiaryOutput.parse("{\"title\": \"a\"}"))
        assertEquals(DiaryOutput.Invalid, DiaryOutput.parse(null))
    }

    @Test
    fun lastParagraphOfPreviousEntry() {
        assertEquals("第三段。", lastParagraph("第一段。\n\n第二段。\n第三段。\n\n"))
        assertEquals("", lastParagraph(""))
    }

    @Test
    fun quoteAttachmentUsesContractKeys() {
        val entry = DiaryEntryEntity(id = 1, date = "2026-11-12", title = "雨天", mood = "安静", body = "雨".repeat(200), summary = "", createdAt = 0)
        val quote = diaryQuote(entry, "引用她的日记《%1\$s》：%2\$s")
        assertEquals(AttachmentTypes.QUOTE, quote.type)
        assertEquals(JsonPrimitive("雨天"), quote.payload[QuotePayload.TITLE])
        assertEquals(JsonPrimitive("2026-11-12"), quote.payload[QuotePayload.DATE])
        assertEquals(120, (quote.payload[QuotePayload.TEXT] as JsonPrimitive).content.length)
        assertEquals("引用她的日记《雨天》：" + "雨".repeat(120), (quote.payload[ATTACHMENT_PREVIEW_KEY] as JsonPrimitive).content)
    }
}
