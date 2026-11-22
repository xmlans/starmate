package org.xmyun.starmate.core.model

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelContractTest {

    @Test
    fun backgroundPurposesAreExactlyProactiveDiaryMemory() {
        val background = TurnPurpose.entries.filter { it.isBackground }.toSet()
        assertEquals(setOf(TurnPurpose.PROACTIVE, TurnPurpose.DIARY, TurnPurpose.MEMORY), background)
    }

    @Test
    fun quietHoursAcrossMidnight() {
        val q = QuietHours.DEFAULT // 23:00–08:00
        assertTrue(q.contains(23 * 60))
        assertTrue(q.contains(0))
        assertTrue(q.contains(7 * 60 + 59))
        assertFalse(q.contains(8 * 60))
        assertFalse(q.contains(22 * 60 + 59))
    }

    @Test
    fun quietHoursSameDay() {
        val q = QuietHours(startMinute = 13 * 60, endMinute = 14 * 60)
        assertTrue(q.contains(13 * 60 + 30))
        assertFalse(q.contains(14 * 60))
        assertFalse(q.contains(12 * 60 + 59))
    }

    @Test
    fun attachmentRoundTrips() {
        val a = Attachment(AttachmentTypes.VOICE, buildJsonObject { put("file", "a.mp3") })
        val json = Json.encodeToString(Attachment.serializer(), a)
        assertEquals(a, Json.decodeFromString(Attachment.serializer(), json))
    }

    @Test
    fun tokenUsageAdds() {
        val sum = TokenUsage(10, 5, 2) + TokenUsage(1, 1, 1)
        assertEquals(TokenUsage(11, 6, 3), sum)
    }

    @Test
    fun clinginessCaps() {
        assertEquals(listOf(0, 1, 2, 4), Clinginess.entries.map { it.maxPerDay })
    }

    @Test
    fun previewTextFallsBackToAttachment() {
        val voice = Attachment(
            AttachmentTypes.VOICE,
            buildJsonObject { put(ATTACHMENT_PREVIEW_KEY, "[语音] 想你了") },
        )
        val base = ChatMessage(
            role = Role.ASSISTANT,
            rawText = "<voice>想你了</voice>",
            displayText = "",
            attachments = listOf(voice),
            purpose = TurnPurpose.PROACTIVE,
            createdAt = Instant.EPOCH,
        )
        assertEquals("[语音] 想你了", base.previewText())
        assertEquals("在吗", base.copy(displayText = "在吗").previewText())
        assertEquals("", base.copy(attachments = emptyList()).previewText())
    }
}
