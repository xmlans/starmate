package org.xmyun.starmate.feature.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    // ---- memories ----

    @Query("SELECT * FROM memories ORDER BY id")
    suspend fun allMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY id")
    fun observeMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun memory(id: Long): MemoryEntity?

    @Query("SELECT * FROM memories WHERE id = :id")
    fun observeMemory(id: Long): Flow<MemoryEntity?>

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity): Int

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemory(id: Long): Int

    @Query("UPDATE memories SET archived = 1 WHERE id = :id AND pinned = 0")
    suspend fun archiveMemory(id: Long): Int

    @Query(
        "UPDATE memories SET invalidAt = :now, archived = 1, updatedAt = :now " +
            "WHERE id = :id AND pinned = 0 AND invalidAt IS NULL",
    )
    suspend fun invalidateMemory(id: Long, now: Long): Int

    @Query(
        "UPDATE memories SET lastUsedAt = :now, hits = hits + 1, " +
            "archived = CASE WHEN invalidAt IS NULL THEN 0 ELSE archived END WHERE id = :id",
    )
    suspend fun touch(id: Long, now: Long): Int

    @Query("UPDATE memories SET nudgedAt = :now WHERE id IN (:ids)")
    suspend fun markNudged(ids: List<Long>, now: Long)

    @Query("SELECT * FROM memories WHERE createdAt >= :from AND createdAt < :to ORDER BY id")
    suspend fun memoriesCreatedBetween(from: Long, to: Long): List<MemoryEntity>

    // ---- links ----

    @Query("SELECT * FROM links")
    suspend fun allLinks(): List<LinkEntity>

    @Query("SELECT * FROM links WHERE a = :id OR b = :id")
    suspend fun linksOf(id: Long): List<LinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLink(link: LinkEntity)

    @Query("DELETE FROM links WHERE a = :id OR b = :id")
    suspend fun deleteLinksOf(id: Long)

    // ---- episodes ----

    @Insert
    suspend fun insertEpisode(episode: EpisodeEntity): Long

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun episode(id: Long): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE id IN (:ids) ORDER BY id")
    suspend fun episodesByIds(ids: List<Long>): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun episodesAfter(afterId: Long, limit: Int): List<EpisodeEntity>

    @Query("SELECT COUNT(*) FROM episodes WHERE id > :afterId")
    suspend fun countEpisodesAfter(afterId: Long): Int

    @Query("SELECT COUNT(*) FROM episodes WHERE id > :afterId AND ts <= :untilTs")
    suspend fun countEpisodesAfterUntil(afterId: Long, untilTs: Long): Int

    @Query("SELECT COUNT(*) FROM episodes")
    suspend fun countEpisodes(): Int

    @Query("SELECT * FROM episodes WHERE ts >= :from AND ts < :to ORDER BY id")
    suspend fun episodesBetween(from: Long, to: Long): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE ts >= :from AND ts <= :to ORDER BY id LIMIT :limit")
    suspend fun episodesInSpan(from: Long, to: Long, limit: Int): List<EpisodeEntity>

    @Query("SELECT * FROM episodes ORDER BY id DESC LIMIT :limit")
    suspend fun recentEpisodes(limit: Int): List<EpisodeEntity>

    @Query(
        "SELECT id FROM episodes WHERE userText LIKE :pattern ESCAPE '\\' OR replyText LIKE :pattern ESCAPE '\\' " +
            "OR keys LIKE :pattern ESCAPE '\\' ORDER BY id DESC LIMIT :limit",
    )
    suspend fun episodeIdsLike(pattern: String, limit: Int): List<Long>

    @Query("UPDATE episodes SET keys = :keys WHERE id = :id")
    suspend fun updateEpisodeKeys(id: Long, keys: String)

    @Query("SELECT * FROM episodes WHERE replyText = :replyText ORDER BY id DESC LIMIT :limit")
    suspend fun episodesWithReply(replyText: String, limit: Int): List<EpisodeEntity>

    @Query("UPDATE episodes SET replyText = :replyText, keys = :keys WHERE id = :id")
    suspend fun updateEpisodeReply(id: Long, replyText: String, keys: String)

    @Query("SELECT id FROM episodes ORDER BY id DESC LIMIT 1 OFFSET :offset")
    suspend fun episodeIdAtOffsetFromNewest(offset: Int): Long?

    // ---- gists ----

    @Insert
    suspend fun insertGist(gist: GistEntity): Long

    @Query("SELECT * FROM gists WHERE endTs >= :from AND startTs <= :to ORDER BY startTs LIMIT :limit")
    suspend fun gistsOverlapping(from: Long, to: Long, limit: Int): List<GistEntity>

    @Query("SELECT * FROM gists ORDER BY endTs DESC LIMIT :limit")
    suspend fun recentGists(limit: Int): List<GistEntity>

    // ---- vectors ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putVectors(vectors: List<VectorEntity>)

    @Query("SELECT * FROM vectors WHERE kind = :kind")
    suspend fun vectors(kind: String): List<VectorEntity>

    @Query("SELECT * FROM vectors WHERE kind = :kind AND refId IN (:ids)")
    suspend fun vectorsFor(kind: String, ids: List<Long>): List<VectorEntity>

    @Query("DELETE FROM vectors WHERE kind = :kind AND refId = :refId")
    suspend fun deleteVector(kind: String, refId: Long)

    @Query("DELETE FROM vectors")
    suspend fun deleteAllVectors()

    @Query("SELECT COUNT(*) FROM vectors")
    suspend fun countVectors(): Int

    @Query(
        "SELECT m.* FROM memories m LEFT JOIN vectors v ON v.kind = 'm' AND v.refId = m.id " +
            "WHERE v.refId IS NULL ORDER BY m.id DESC LIMIT :limit",
    )
    suspend fun unindexedMemories(limit: Int): List<MemoryEntity>

    @Query(
        "SELECT e.* FROM episodes e LEFT JOIN vectors v ON v.kind = 'e' AND v.refId = e.id " +
            "WHERE v.refId IS NULL ORDER BY e.id DESC LIMIT :limit",
    )
    suspend fun unindexedEpisodes(limit: Int): List<EpisodeEntity>

    @Query(
        "SELECT g.* FROM gists g LEFT JOIN vectors v ON v.kind = 'g' AND v.refId = g.id " +
            "WHERE v.refId IS NULL ORDER BY g.id DESC LIMIT :limit",
    )
    suspend fun unindexedGists(limit: Int): List<GistEntity>

    // ---- state ----

    @Query("SELECT * FROM state WHERE id = 1")
    suspend fun state(): StateEntity?

    @Query("SELECT * FROM state WHERE id = 1")
    fun observeState(): Flow<StateEntity?>

    /** 只用于初始化。其余修改用下面的定向 UPDATE，避免覆盖触发器维护的 revision。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStateIfAbsent(state: StateEntity)

    @Query("UPDATE state SET digest = :digest, revision = revision + 1 WHERE id = 1")
    suspend fun setDigest(digest: String)

    @Query(
        "UPDATE state SET consolidatedUpto = :upto, consolidations = consolidations + :increment, " +
            "failEpisode = 0, failCount = 0 WHERE id = 1",
    )
    suspend fun advanceCursor(upto: Long, increment: Int)

    @Query("UPDATE state SET batchLimit = :batchLimit, failEpisode = :failEpisode, failCount = :failCount WHERE id = 1")
    suspend fun setFailure(batchLimit: Int, failEpisode: Long, failCount: Int)

    @Query("UPDATE state SET batchLimit = :batchLimit WHERE id = 1")
    suspend fun setBatchLimit(batchLimit: Int)

    @Query(
        "UPDATE state SET digest = '', consolidatedUpto = 0, consolidations = 0, batchLimit = 0, " +
            "failEpisode = 0, failCount = 0, revision = revision + 1 WHERE id = 1",
    )
    suspend fun resetState()

    // ---- 清空 ----

    @Query("DELETE FROM memories")
    suspend fun deleteAllMemories()

    @Query("DELETE FROM episodes")
    suspend fun deleteAllEpisodes()

    @Query("DELETE FROM gists")
    suspend fun deleteAllGists()

    @Query("DELETE FROM links")
    suspend fun deleteAllLinks()
}
