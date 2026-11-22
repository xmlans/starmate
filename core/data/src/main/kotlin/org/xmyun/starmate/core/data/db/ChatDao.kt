package org.xmyun.starmate.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    // ---- messages ----

    @Query("SELECT * FROM messages ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecentDesc(limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recentDesc(limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE createdAt >= :fromMillis AND createdAt < :toMillis ORDER BY createdAt ASC, id ASC")
    suspend fun between(fromMillis: Long, toMillis: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun messageById(id: Long): MessageEntity?

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("SELECT MAX(createdAt) FROM messages")
    suspend fun lastMessageAt(): Long?

    @Query("SELECT MAX(createdAt) FROM messages WHERE role = :role")
    suspend fun lastMessageAtForRole(role: String): Long?

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun messageCount(): Int

    // ---- companion ----

    @Query("SELECT * FROM companion WHERE id = :id")
    fun observeCompanion(id: Long): Flow<CompanionEntity?>

    @Query("SELECT * FROM companion WHERE id = :id")
    suspend fun companion(id: Long): CompanionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCompanionIfAbsent(companion: CompanionEntity)

    @Upsert
    suspend fun upsertCompanion(companion: CompanionEntity)

    // ---- usage ----

    @Insert
    suspend fun insertUsage(usage: UsageEntity)

    @Query(
        "SELECT COALESCE(SUM(inputTokens + outputTokens), 0) FROM usage " +
            "WHERE purpose = :purpose AND at >= :fromMillis AND at < :toMillis",
    )
    suspend fun sumTokens(purpose: String, fromMillis: Long, toMillis: Long): Long

    @Query("SELECT * FROM usage WHERE at >= :fromMillis ORDER BY at ASC")
    fun observeUsageSince(fromMillis: Long): Flow<List<UsageEntity>>
}
