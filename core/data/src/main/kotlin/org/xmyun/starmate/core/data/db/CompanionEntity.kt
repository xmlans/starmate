package org.xmyun.starmate.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.xmyun.starmate.core.model.CompanionProfile

@Entity(tableName = "companion")
data class CompanionEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val avatarUri: String?,
    val persona: String,
    val relationshipNotes: String,
    val userNickname: String,
)

fun CompanionEntity.toModel(): CompanionProfile = CompanionProfile(
    id = id,
    name = name,
    avatarUri = avatarUri,
    persona = persona,
    relationshipNotes = relationshipNotes,
    userNickname = userNickname,
)

fun CompanionProfile.toEntity(): CompanionEntity = CompanionEntity(
    id = id,
    name = name,
    avatarUri = avatarUri,
    persona = persona,
    relationshipNotes = relationshipNotes,
    userNickname = userNickname,
)
