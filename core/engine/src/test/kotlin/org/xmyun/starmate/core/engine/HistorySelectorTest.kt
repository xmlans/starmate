package org.xmyun.starmate.core.engine

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.MessageStatus
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TurnPurpose

class HistorySelectorTest {

    private fun msg(
        id: Long,
        raw: String,
        role: Role = if (id % 2 == 1L) Role.USER else Role.ASSISTANT,
        status: MessageStatus = MessageStatus.COMPLETE,
    ) = ChatMessage(
        id = id,
        role = role,
        rawText = raw,
        displayText = raw,
        purpose = TurnPurpose.CHAT,
        createdAt = Instant.ofEpochSecond(id),
        status = status,
    )

    // 每条 "一二三四" 估算为 4 token
    private val five = (1L..5L).map { msg(it, "一二三四") }

    @Test
    fun budgetForThreeReturnsLastThreeInOrder() {
        val picked = HistorySelector.select(five, tokenBudget = 12)
        assertEquals(listOf(3L, 4L, 5L), picked.map { it.id })
    }

    @Test
    fun tinyBudgetStillKeepsTwo() {
        val picked = HistorySelector.select(five, tokenBudget = 1)
        assertEquals(listOf(4L, 5L), picked.map { it.id })
    }

    @Test
    fun failedAndStreamingAreSkipped() {
        val list = listOf(
            msg(1, "一二三四"),
            msg(2, "一二三四", status = MessageStatus.FAILED),
            msg(3, "一二三四"),
            msg(4, "一二", status = MessageStatus.STREAMING),
        )
        val picked = HistorySelector.select(list, tokenBudget = 100)
        assertEquals(listOf(1L, 3L), picked.map { it.id })
    }

    @Test
    fun blankRawTextIsSkipped() {
        val list = listOf(msg(1, "一二三四"), msg(2, "   "), msg(3, "一二三四"))
        val picked = HistorySelector.select(list, tokenBudget = 100)
        assertEquals(listOf(1L, 3L), picked.map { it.id })
    }

    @Test
    fun usesRawTextForModel() {
        val m = msg(1, "原始").copy(displayText = "显示")
        assertEquals("原始", HistorySelector.toLlm(listOf(m)).single().text)
    }
}
