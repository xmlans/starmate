package org.xmyun.starmate.core.llm.http

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.core.model.LlmMessage
import org.xmyun.starmate.core.model.LlmRequest
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.SystemPrompt
import org.xmyun.starmate.core.model.ToolCall
import org.xmyun.starmate.core.model.ToolSpec

class MessageShaperTest {

    private val call = ToolCall("c1", "weather_now", buildJsonObject { })
    private val tool = ToolSpec("weather_now", "d", buildJsonObject { })

    @Test
    fun assistantWithToolCallsIsNotMergedWithFollowingAssistant() {
        val shaped = MessageShaper.mergeConsecutive(
            listOf(
                LlmMessage(Role.ASSISTANT, "先查", toolCalls = listOf(call)),
                LlmMessage(Role.ASSISTANT, "接着说"),
            ),
        )
        assertEquals(2, shaped.size)
    }

    @Test
    fun plainAssistantMergesIntoFollowingToolCallingAssistant() {
        val shaped = MessageShaper.mergeConsecutive(
            listOf(LlmMessage(Role.ASSISTANT, "嗯"), LlmMessage(Role.ASSISTANT, "我查一下", toolCalls = listOf(call))),
        )
        assertEquals(listOf(LlmMessage(Role.ASSISTANT, "嗯\n\n我查一下", toolCalls = listOf(call))), shaped)
    }

    @Test
    fun toolResultsStaySeparateWhenToolsGiven() {
        val request = LlmRequest(
            SystemPrompt(""),
            listOf(
                LlmMessage(Role.ASSISTANT, "", toolCalls = listOf(call, call.copy(id = "c2"))),
                LlmMessage(Role.TOOL, "晴", toolCallId = "c1"),
                LlmMessage(Role.TOOL, "25 度", toolCallId = "c2"),
            ),
            tools = listOf(tool),
        )
        val shaped = MessageShaper.prepare(request)
        assertEquals(listOf(Role.ASSISTANT, Role.TOOL, Role.TOOL), shaped.map { it.role })
    }

    @Test
    fun flattenRemovesAllToolStructure() {
        val request = LlmRequest(
            SystemPrompt(""),
            listOf(
                LlmMessage(Role.ASSISTANT, "", toolCalls = listOf(call)),
                LlmMessage(Role.TOOL, "晴", toolCallId = "c1"),
            ),
        )
        val shaped = MessageShaper.prepare(request)
        assertTrue(shaped.none { it.toolCalls.isNotEmpty() || it.role == Role.TOOL })
        assertTrue(shaped[0].text.contains("weather_now"))
        assertEquals(Role.USER, shaped[1].role)
        assertTrue(shaped[1].text.contains("weather_now"))
    }

    @Test
    fun emptyTurnsDroppedAndUserFirst() {
        val shaped = MessageShaper.ensureUserFirst(
            MessageShaper.prepare(LlmRequest(SystemPrompt(""), listOf(LlmMessage(Role.USER, " "), LlmMessage(Role.ASSISTANT, "嗨")))),
        )
        assertEquals(listOf(Role.USER, Role.ASSISTANT), shaped.map { it.role })
        assertEquals(MessageShaper.PLACEHOLDER_USER_TEXT, shaped[0].text)
        assertEquals(listOf(Role.USER), MessageShaper.ensureUserFirst(emptyList()).map { it.role })
    }

    @Test
    fun errorPrefixes() {
        assertTrue(MessageShaper.isErrorResult("[出错] x"))
        assertTrue(MessageShaper.isErrorResult("[error] x"))
        assertFalse(MessageShaper.isErrorResult("出错了"))
    }
}
