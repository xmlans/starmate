package org.xmyun.starmate.core.model

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

const val DEFAULT_COMPANION_ID = 1L

@Serializable
data class Attachment(val type: String, val payload: JsonObject)

object AttachmentTypes {
    const val VOICE = "voice"
    const val QUOTE = "quote"
    const val ONBOARDING_SUMMARY = "onboarding_summary"
}

/** QUOTE 附件 payload 的键。除这三个外还应带 [ATTACHMENT_PREVIEW_KEY]。 */
object QuotePayload {
    const val TITLE = "title"
    /** 被引用的正文摘录。 */
    const val TEXT = "text"
    /** ISO 日期，如 2026-11-12。 */
    const val DATE = "date"
}

/**
 * 附件 payload 里可选的一行纯文字。两个用途：
 * 1. 通知、记忆这类只能显示文字的地方用它代表这个附件。
 * 2. 用户消息带附件时，引擎把它拼在用户文字之前发给模型。
 */
const val ATTACHMENT_PREVIEW_KEY = "preview"

enum class MessageStatus { STREAMING, COMPLETE, FAILED }

data class ChatMessage(
    val id: Long = 0,
    val companionId: Long = DEFAULT_COMPANION_ID,
    val role: Role,                 // 只会是 USER 或 ASSISTANT
    val rawText: String,            // 发给模型的文字：助手消息是模型原始输出；用户消息是附件 preview + 用户文字
    val displayText: String,        // 给用户看的文字：助手消息经 ReplyPostProcessor 加工；用户消息是用户输入的原文
    val attachments: List<Attachment> = emptyList(),
    val purpose: TurnPurpose,
    val createdAt: Instant,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val errorKind: LlmErrorKind? = null,
)

/** 一行预览：有显示文字就用它，否则取第一个带 preview 的附件，都没有则为空串。 */
fun ChatMessage.previewText(): String =
    displayText.ifBlank {
        attachments.firstNotNullOfOrNull { attachment ->
            (attachment.payload[ATTACHMENT_PREVIEW_KEY] as? JsonPrimitive)?.contentOrNull
        } ?: ""
    }

interface MessageStore {
    /** 最近 limit 条，按时间正序。 */
    fun observeRecent(limit: Int): Flow<List<ChatMessage>>
    suspend fun recent(limit: Int): List<ChatMessage>
    suspend fun between(from: Instant, to: Instant): List<ChatMessage>
    suspend fun byId(id: Long): ChatMessage?

    /** 传入的 id 被忽略，返回新 id。 */
    suspend fun insert(message: ChatMessage): Long
    suspend fun update(message: ChatMessage)
    suspend fun delete(id: Long)
    suspend fun lastMessageAt(role: Role? = null): Instant?
}

/** 其他页面想往聊天输入栏里塞一个附件时用，例如日记页的“回复这篇”。 */
interface ChatDraftBus {
    val pending: StateFlow<Attachment?>
    fun offer(attachment: Attachment)
    fun consume(): Attachment?
}
