package org.xmyun.starmate.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.MessageStatus
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TurnPurpose

/** 枚举存名字，未知值读出时回落到安全的默认值；时间存毫秒。 */
@Entity(
    tableName = "messages",
    indices = [Index("companionId"), Index("createdAt")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companionId: Long,
    val role: String,
    val rawText: String,
    val displayText: String,
    val attachments: List<Attachment>,
    val purpose: String,
    val createdAt: Long,
    val status: String,
    val errorKind: String?,
)

fun MessageEntity.toModel(): ChatMessage = ChatMessage(
    id = id,
    companionId = companionId,
    role = Role.entries.firstOrNull { it.name == role } ?: Role.ASSISTANT,
    rawText = rawText,
    displayText = displayText,
    attachments = attachments,
    purpose = TurnPurpose.entries.firstOrNull { it.name == purpose } ?: TurnPurpose.CHAT,
    createdAt = Instant.ofEpochMilli(createdAt),
    status = MessageStatus.entries.firstOrNull { it.name == status } ?: MessageStatus.FAILED,
    errorKind = errorKind?.let { name -> LlmErrorKind.entries.firstOrNull { it.name == name } },
)

fun ChatMessage.toEntity(): MessageEntity = MessageEntity(
    id = id,
    companionId = companionId,
    role = role.name,
    rawText = rawText,
    displayText = displayText,
    attachments = attachments,
    purpose = purpose.name,
    createdAt = createdAt.toEpochMilli(),
    status = status.name,
    errorKind = errorKind?.name,
)
