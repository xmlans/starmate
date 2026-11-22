package org.xmyun.starmate.core.llm.http

import org.xmyun.starmate.core.model.LlmMessage
import org.xmyun.starmate.core.model.LlmRequest
import org.xmyun.starmate.core.model.Role

/**
 * 发请求前整理消息历史，两种客户端共用：
 * 1. 本次没给工具时，历史里的工具调用和工具结果改写成普通文字（服务端会拒绝没有工具定义的工具消息）。
 * 2. 连续同角色的消息合并，文字用空行连接；带工具调用的助手消息之后不再并入别的消息。
 * 3. 完全为空的用户、助手消息丢掉（空内容会被拒绝）。
 */
object MessageShaper {

    /** Anthropic 要求第一条是用户消息；历史以她的消息开头时补这一条。 */
    const val PLACEHOLDER_USER_TEXT = "[conversation start]"

    private val ERROR_PREFIXES = listOf("[出错] ", "[error] ")

    fun prepare(request: LlmRequest): List<LlmMessage> {
        val base = if (request.tools.isEmpty()) flattenToolHistory(request.messages) else request.messages
        return mergeConsecutive(base.filterNot { it.isEmptyTurn() })
    }

    /** 工具调用变成助手文字里的说明，工具结果变成用户侧文字并标明是哪个工具的结果。 */
    fun flattenToolHistory(messages: List<LlmMessage>): List<LlmMessage> {
        val namesById = mutableMapOf<String, String>()
        return messages.map { message ->
            when (message.role) {
                Role.ASSISTANT -> if (message.toolCalls.isEmpty()) {
                    message
                } else {
                    message.toolCalls.forEach { namesById[it.id] = it.name }
                    val calls = message.toolCalls.joinToString("\n") { "[tool call] ${it.name} ${it.arguments}" }
                    message.copy(text = joinText(message.text, calls), toolCalls = emptyList())
                }
                Role.TOOL -> {
                    val name = message.toolCallId?.let { namesById[it] } ?: message.toolCallId ?: "tool"
                    LlmMessage(Role.USER, "[tool result] $name\n${message.text}")
                }
                else -> message
            }
        }
    }

    /**
     * 合并连续同角色消息。用户消息之间总能合并；助手消息只有前一条不带工具调用时才合并，
     * 这样工具调用始终在这一轮助手消息的末尾，紧跟着它的工具结果。工具结果和 system 消息不合并。
     */
    fun mergeConsecutive(messages: List<LlmMessage>): List<LlmMessage> {
        val out = mutableListOf<LlmMessage>()
        for (message in messages) {
            val last = out.lastOrNull()
            val mergeable = last != null && last.role == message.role && when (message.role) {
                Role.USER -> true
                Role.ASSISTANT -> last.toolCalls.isEmpty()
                else -> false
            }
            if (mergeable && last != null) {
                out[out.lastIndex] = last.copy(
                    text = joinText(last.text, message.text),
                    images = last.images + message.images,
                    toolCalls = last.toolCalls + message.toolCalls,
                )
            } else {
                out += message
            }
        }
        return out
    }

    /** 第一条非 system 消息不是用户侧（用户或工具结果）时，在最前面补一条占位用户消息。没有消息时也补。 */
    fun ensureUserFirst(messages: List<LlmMessage>): List<LlmMessage> {
        val first = messages.firstOrNull { it.role != Role.SYSTEM }
        return if (first == null || first.role == Role.ASSISTANT) {
            listOf(LlmMessage(Role.USER, PLACEHOLDER_USER_TEXT)) + messages
        } else {
            messages
        }
    }

    /** 引擎用文字前缀标记工具出错（LlmMessage 没有出错字段）。 */
    fun isErrorResult(text: String): Boolean = ERROR_PREFIXES.any { text.startsWith(it) }

    private fun LlmMessage.isEmptyTurn(): Boolean =
        (role == Role.USER || role == Role.ASSISTANT) && text.isBlank() && images.isEmpty() && toolCalls.isEmpty()

    private fun joinText(a: String, b: String): String = listOf(a, b).filter { it.isNotBlank() }.joinToString("\n\n")
}
