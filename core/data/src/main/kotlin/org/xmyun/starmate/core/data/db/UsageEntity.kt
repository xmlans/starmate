package org.xmyun.starmate.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.xmyun.starmate.core.model.UsageEntry

/** 用量账本的一条记录。at 为毫秒时间戳。 */
@Entity(
    tableName = "usage",
    indices = [Index("at"), Index("purpose")],
)
data class UsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purpose: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedInputTokens: Int,
    val ttsChars: Int,
    val at: Long,
)

fun UsageEntry.toEntity(): UsageEntity = UsageEntity(
    purpose = purpose.name,
    model = model,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cachedInputTokens = cachedInputTokens,
    ttsChars = ttsChars,
    at = at.toEpochMilli(),
)
