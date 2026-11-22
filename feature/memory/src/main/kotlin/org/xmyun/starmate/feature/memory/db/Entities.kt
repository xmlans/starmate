package org.xmyun.starmate.feature.memory.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.xmyun.starmate.feature.memory.model.Episode
import org.xmyun.starmate.feature.memory.model.Gist
import org.xmyun.starmate.feature.memory.model.Mem

@Entity(tableName = "memories", indices = [Index("archived"), Index("createdAt")])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val category: String,
    val importance: Int,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long,
    val hits: Int,
    val tags: String,
    val archived: Boolean,
    val source: String,
    val eventAt: Long?,
    val invalidAt: Long?,
    val episodeId: Long?,
    val emotion: Int,
    val nudgedAt: Long?,
) {
    fun toMem() = Mem(
        id, content, category, importance, pinned, createdAt, updatedAt, lastUsedAt, hits, tags,
        archived, source, eventAt, invalidAt, episodeId, emotion, nudgedAt,
    )
}

/** 一轮原话。keys 是检索用的词项（含事实反哺进来的关键词）。 */
@Entity(tableName = "episodes", indices = [Index("ts")])
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val userText: String,
    val replyText: String,
    val keys: String,
    val purpose: String,
) {
    fun toEpisode() = Episode(id, ts, userText, replyText, keys, purpose)
}

@Entity(tableName = "gists", indices = [Index("endTs")])
data class GistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTs: Long,
    val endTs: Long,
    val firstEpisode: Long,
    val lastEpisode: Long,
    val summary: String,
    val keys: String,
) {
    fun toGist() = Gist(id, startTs, endTs, firstEpisode, lastEpisode, summary, keys)
}

@Entity(tableName = "links", primaryKeys = ["a", "b"])
data class LinkEntity(val a: Long, val b: Long, val weight: Double)

/** kind：m 记忆、e 原话、g 梗概。inputHash 是生成向量时输入文字的哈希，内容变了就作废。 */
@Entity(tableName = "vectors", primaryKeys = ["kind", "refId"])
data class VectorEntity(
    val kind: String,
    val refId: Long,
    val dim: Int,
    val vec: ByteArray,
    val inputHash: String,
)

/**
 * 只有一行（id = 1）。revision 由数据库触发器在记忆内容变化时递增，用来判断稳定快照是否要重建。
 * batchLimit、failEpisode、failCount 记录整理失败后的二分和跳过进度，进程重启后仍然有效。
 */
@Entity(tableName = "state")
data class StateEntity(
    @PrimaryKey val id: Int = 1,
    val digest: String = "",
    val consolidatedUpto: Long = 0,
    val consolidations: Int = 0,
    val revision: Long = 0,
    val batchLimit: Int = 0,
    val failEpisode: Long = 0,
    val failCount: Int = 0,
)
