package org.xmyun.starmate.core.engine

import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.LlmMessage
import org.xmyun.starmate.core.model.MessageStatus
import org.xmyun.starmate.core.model.Role

/** 从最新往前挑历史消息，累计估算 token 不超过预算，至少保留最近 2 条。 */
object HistorySelector {

    const val MIN_KEEP = 2

    /** 只发 COMPLETE 且 rawText 非空白的用户和助手消息。 */
    fun isEligible(message: ChatMessage): Boolean =
        message.status == MessageStatus.COMPLETE &&
            message.rawText.isNotBlank() &&
            (message.role == Role.USER || message.role == Role.ASSISTANT)

    /** messages 按时间正序；返回值同样按时间正序。 */
    fun select(messages: List<ChatMessage>, tokenBudget: Int): List<ChatMessage> {
        val picked = ArrayList<ChatMessage>()
        var used = 0
        for (message in messages.asReversed()) {
            if (!isEligible(message)) continue
            val cost = TokenEstimator.estimate(message.rawText)
            if (picked.size >= MIN_KEEP && used + cost > tokenBudget) break
            picked += message
            used += cost
        }
        picked.reverse()
        return picked
    }

    fun toLlm(messages: List<ChatMessage>): List<LlmMessage> =
        messages.map { LlmMessage(role = it.role, text = it.rawText) }
}
