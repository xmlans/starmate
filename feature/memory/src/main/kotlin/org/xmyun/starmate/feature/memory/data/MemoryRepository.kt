package org.xmyun.starmate.feature.memory.data

import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.xmyun.starmate.feature.memory.consolidate.MemPatch
import org.xmyun.starmate.feature.memory.consolidate.MemoryOpsStore
import org.xmyun.starmate.feature.memory.consolidate.NewMemory
import org.xmyun.starmate.feature.memory.db.EpisodeEntity
import org.xmyun.starmate.feature.memory.db.FtsTables
import org.xmyun.starmate.feature.memory.db.GistEntity
import org.xmyun.starmate.feature.memory.db.LinkEntity
import org.xmyun.starmate.feature.memory.db.MemoryDatabaseHolder
import org.xmyun.starmate.feature.memory.db.MemoryEntity
import org.xmyun.starmate.feature.memory.db.StateEntity
import org.xmyun.starmate.feature.memory.db.VectorEntity
import org.xmyun.starmate.feature.memory.model.Categories
import org.xmyun.starmate.feature.memory.model.Episode
import org.xmyun.starmate.feature.memory.model.Gist
import org.xmyun.starmate.feature.memory.model.Mem
import org.xmyun.starmate.feature.memory.model.Sources
import org.xmyun.starmate.feature.memory.retrieval.KeywordSearch
import org.xmyun.starmate.feature.memory.retrieval.Vectors
import org.xmyun.starmate.feature.memory.text.MAX_TAGS_CHARS
import org.xmyun.starmate.feature.memory.text.clampEmotion
import org.xmyun.starmate.feature.memory.text.clampImportance
import org.xmyun.starmate.feature.memory.text.cleanContent
import org.xmyun.starmate.feature.memory.text.joinKeys
import org.xmyun.starmate.feature.memory.text.sha256
import org.xmyun.starmate.feature.memory.text.tokens

/** memory.db 的全部读写。所有组件都通过它访问数据库。 */
@Singleton
class MemoryRepository @Inject constructor(
    val holder: MemoryDatabaseHolder,
) : MemoryOpsStore {

    private suspend fun dao() = holder.dao()

    /** 一组写入一起提交，中途出错整批回滚。 */
    suspend fun <T> transaction(block: suspend () -> T): T =
        holder.get().useWriterConnection { transactor -> transactor.immediateTransaction { block() } }

    // ---------- 记忆 ----------

    override suspend fun allMemories(): List<Mem> = dao().allMemories().map { it.toMem() }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMemories(): Flow<List<Mem>> =
        holder.database.filterNotNull().flatMapLatest { db -> db.dao().observeMemories() }.map { list -> list.map { it.toMem() } }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMemory(id: Long): Flow<Mem?> =
        holder.database.filterNotNull().flatMapLatest { db -> db.dao().observeMemory(id) }.map { it?.toMem() }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDigest(): Flow<String> =
        holder.database.filterNotNull().flatMapLatest { db -> db.dao().observeState() }.map { it?.digest ?: "" }

    override suspend fun memory(id: Long): Mem? = dao().memory(id)?.toMem()

    override suspend fun addMemory(memory: NewMemory, now: Long): Long? {
        val content = cleanContent(memory.content)
        if (content.isEmpty()) return null
        return dao().insertMemory(
            MemoryEntity(
                content = content,
                category = memory.category.takeIf { it in Categories.ALL } ?: Categories.OTHER,
                importance = clampImportance(memory.importance),
                pinned = memory.pinned,
                createdAt = now,
                updatedAt = now,
                lastUsedAt = now,
                hits = 0,
                tags = cleanContent(memory.tags, MAX_TAGS_CHARS),
                archived = false,
                source = memory.source.takeIf { it in Sources.ALL } ?: Sources.USER,
                eventAt = memory.eventAt,
                invalidAt = null,
                episodeId = memory.episodeId,
                emotion = clampEmotion(memory.emotion),
                nudgedAt = null,
            ),
        )
    }

    override suspend fun updateMemory(id: Long, patch: MemPatch, now: Long): Boolean {
        val dao = dao()
        val old = dao.memory(id) ?: return false
        val content = patch.content?.let { cleanContent(it) }
        if (patch.content != null && content.isNullOrEmpty()) return false
        val updated = old.copy(
            content = content ?: old.content,
            category = patch.category?.takeIf { it in Categories.ALL } ?: old.category,
            importance = patch.importance?.let { clampImportance(it) } ?: old.importance,
            tags = patch.tags?.let { cleanContent(it, MAX_TAGS_CHARS) } ?: old.tags,
            emotion = patch.emotion?.let { clampEmotion(it) } ?: old.emotion,
            pinned = patch.pinned ?: old.pinned,
            archived = patch.archived ?: old.archived,
            hits = patch.hits ?: old.hits,
            eventAt = patch.eventAt ?: old.eventAt,
            updatedAt = now,
        )
        val changed = dao.updateMemory(updated) > 0
        if (changed && (updated.content != old.content || updated.tags != old.tags)) dao.deleteVector("m", id)
        return changed
    }

    /** 用户在记忆页上改动：可以清掉事件日期、恢复失效的记忆。 */
    suspend fun replaceByUser(mem: Mem, now: Long) {
        val dao = dao()
        val old = dao.memory(mem.id) ?: return
        val content = cleanContent(mem.content)
        if (content.isEmpty()) return
        val updated = old.copy(
            content = content,
            importance = clampImportance(mem.importance),
            pinned = mem.pinned,
            archived = mem.archived,
            invalidAt = mem.invalidAt,
            updatedAt = now,
        )
        dao.updateMemory(updated)
        if (updated.content != old.content) dao.deleteVector("m", mem.id)
    }

    override suspend fun deleteMemory(id: Long): Boolean = transaction {
        val dao = dao()
        val removed = dao.deleteMemory(id) > 0
        if (removed) {
            dao.deleteVector("m", id)
            dao.deleteLinksOf(id)
        }
        removed
    }

    override suspend fun archiveMemory(id: Long): Boolean = dao().archiveMemory(id) > 0

    override suspend fun invalidateMemory(id: Long, now: Long): Boolean = dao().invalidateMemory(id, now) > 0

    override suspend fun touch(ids: Collection<Long>, now: Long) {
        val dao = dao()
        ids.forEach { dao.touch(it, now) }
    }

    suspend fun markNudged(ids: Collection<Long>, now: Long) {
        if (ids.isNotEmpty()) dao().markNudged(ids.toList(), now)
    }

    // 关心提醒放进提示词时先暂存，等这一轮对话成功后才标记为“提过了”。
    // 否则这一轮失败或被取消时，提醒没送到，却要等 3 天才会再出现。
    @Volatile
    private var stagedNudge: Pair<List<Long>, Long>? = null

    /** 新一轮开始拼提示词时调用：上一轮没提交的暂存作废，换成这一轮的（没有就传空列表）。 */
    fun stageNudge(ids: Collection<Long>, now: Long) {
        stagedNudge = if (ids.isEmpty()) null else ids.toList() to now
    }

    /** 一轮对话成功后调用。 */
    suspend fun commitStagedNudge() {
        val staged = stagedNudge ?: return
        stagedNudge = null
        markNudged(staged.first, staged.second)
    }

    suspend fun memoriesCreatedBetween(from: Long, to: Long): List<Mem> =
        dao().memoriesCreatedBetween(from, to).map { it.toMem() }

    // ---------- 联想 ----------

    override suspend fun link(a: Long, b: Long, weight: Double) {
        if (a == b) return
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        val dao = dao()
        val existing = dao.linksOf(lo).firstOrNull { it.a == lo && it.b == hi }?.weight ?: 0.0
        dao.putLink(LinkEntity(lo, hi, maxOf(existing, weight.coerceIn(0.0, 1.0))))
    }

    override suspend fun links(): Map<Long, List<Pair<Long, Double>>> {
        val result = HashMap<Long, MutableList<Pair<Long, Double>>>()
        for (l in dao().allLinks()) {
            result.getOrPut(l.a) { mutableListOf() } += l.b to l.weight
            result.getOrPut(l.b) { mutableListOf() } += l.a to l.weight
        }
        return result
    }

    override suspend fun relink(old: Long, new: Long) {
        val dao = dao()
        for (l in dao.linksOf(old)) {
            val other = if (l.a == old) l.b else l.a
            if (other != new) link(new, other, l.weight)
        }
        dao.deleteLinksOf(old)
    }

    // ---------- 原话 ----------

    suspend fun addEpisode(ts: Long, userText: String, replyText: String, purpose: String): Long = transaction {
        val keys = joinKeys(tokens("$userText $replyText"))
        val id = dao().insertEpisode(EpisodeEntity(ts = ts, userText = userText, replyText = replyText, keys = keys, purpose = purpose))
        holder.get().useWriterConnection { connection ->
            connection.usePrepared("INSERT INTO ${FtsTables.EPISODE_FTS}(rowid, body, keys) VALUES (?, ?, ?)") { st ->
                st.bindLong(1, id)
                st.bindText(2, "$userText\n$replyText")
                st.bindText(3, keys)
                st.step()
            }
        }
        id
    }

    /**
     * 用户举报了她的一条回复：在原话里把这段回复清空（保留用户说的那一句），重建词项、全文索引，删掉向量，
     * 之后检索不到、也不会再被回想起来。按全文相同来找，最多处理最近 [limit] 轮。返回改动的轮数。
     */
    suspend fun redactReply(replyText: String, limit: Int = 20): Int {
        if (replyText.isBlank()) return 0
        return transaction {
            val dao = dao()
            val hits = dao.episodesWithReply(replyText, limit)
            for (episode in hits) {
                val keys = joinKeys(tokens(episode.userText))
                dao.updateEpisodeReply(episode.id, "", keys)
                dao.deleteVector("e", episode.id)
                holder.get().useWriterConnection { connection ->
                    connection.usePrepared("UPDATE ${FtsTables.EPISODE_FTS} SET body = ?, keys = ? WHERE rowid = ?") { st ->
                        st.bindText(1, episode.userText)
                        st.bindText(2, keys)
                        st.bindLong(3, episode.id)
                        st.step()
                    }
                }
            }
            hits.size
        }
    }

    suspend fun episode(id: Long): Episode? = dao().episode(id)?.toEpisode()

    suspend fun episodesByIds(ids: Collection<Long>): List<Episode> =
        ids.toList().chunked(500).flatMap { chunk -> dao().episodesByIds(chunk).map { it.toEpisode() } }.sortedBy { it.id }

    suspend fun episodesAfter(afterId: Long, limit: Int): List<Episode> = dao().episodesAfter(afterId, limit).map { it.toEpisode() }

    suspend fun countEpisodesAfter(afterId: Long): Int = dao().countEpisodesAfter(afterId)

    suspend fun countEpisodesAfterUntil(afterId: Long, untilTs: Long): Int = dao().countEpisodesAfterUntil(afterId, untilTs)

    suspend fun countEpisodes(): Int = dao().countEpisodes()

    suspend fun episodesBetween(from: Long, to: Long): List<Episode> = dao().episodesBetween(from, to).map { it.toEpisode() }

    suspend fun episodesInSpan(from: Long, to: Long, limit: Int): List<Episode> =
        dao().episodesInSpan(from, to, limit).map { it.toEpisode() }

    suspend fun recentEpisodes(limit: Int): List<Episode> = dao().recentEpisodes(limit).map { it.toEpisode() }.sortedBy { it.id }

    /** 最近 window 轮原话里最早那一轮的编号；不足 window 轮时为 0。 */
    suspend fun windowStart(window: Int): Long = dao().episodeIdAtOffsetFromNewest(maxOf(0, window - 1)) ?: 0

    override suspend fun expandEpisodeKeys(episodeId: Long, text: String) {
        val dao = dao()
        val episode = dao.episode(episodeId) ?: return
        val keys = joinKeys(episode.keys.split(' ').filter { it.isNotEmpty() }.toSet() + tokens(text))
        if (keys == episode.keys) return
        dao.updateEpisodeKeys(episodeId, keys)
        dao.deleteVector("e", episodeId)
        holder.get().useWriterConnection { connection ->
            connection.usePrepared("UPDATE ${FtsTables.EPISODE_FTS} SET keys = ? WHERE rowid = ?") { st ->
                st.bindText(1, keys)
                st.bindLong(2, episodeId)
                st.step()
            }
        }
    }

    /**
     * 关键词候选：至少 3 个字符的词走 FTS5 trigram 的 MATCH，更短的词走 LIKE（同时查原文和词项）。
     * 返回命中的原话编号，最近的在前，最多 limit 条。
     */
    suspend fun keywordCandidates(query: String, limit: Int = 500): List<Long> {
        val terms = KeywordSearch.split(query)
        if (terms.isEmpty) return emptyList()
        val found = LinkedHashSet<Long>()
        val db = holder.get()
        if (terms.match.isNotEmpty()) {
            db.useReaderConnection { connection ->
                connection.usePrepared(
                    "SELECT rowid FROM ${FtsTables.EPISODE_FTS} WHERE ${FtsTables.EPISODE_FTS} MATCH ? ORDER BY rowid DESC LIMIT ?",
                ) { st ->
                    st.bindText(1, KeywordSearch.matchExpression(terms.match))
                    st.bindLong(2, limit.toLong())
                    while (st.step()) found += st.getLong(0)
                }
            }
        }
        if (terms.like.isNotEmpty()) {
            val clause = terms.like.joinToString(" OR ") {
                "userText LIKE ? ESCAPE '\\' OR replyText LIKE ? ESCAPE '\\' OR keys LIKE ? ESCAPE '\\'"
            }
            db.useReaderConnection { connection ->
                connection.usePrepared("SELECT id FROM episodes WHERE $clause ORDER BY id DESC LIMIT ?") { st ->
                    var index = 1
                    for (term in terms.like) {
                        val pattern = KeywordSearch.likePattern(term)
                        repeat(3) { st.bindText(index++, pattern) }
                    }
                    st.bindLong(index, limit.toLong())
                    while (st.step()) found += st.getLong(0)
                }
            }
        }
        return found.sortedDescending().take(limit)
    }

    // ---------- 梗概 ----------

    suspend fun addGist(batch: List<Episode>, summary: String): Long? {
        val text = cleanContent(summary, 200)
        if (text.isEmpty() || batch.isEmpty()) return null
        return dao().insertGist(
            GistEntity(
                startTs = batch.minOf { it.ts },
                endTs = batch.maxOf { it.ts },
                firstEpisode = batch.first().id,
                lastEpisode = batch.last().id,
                summary = text,
                keys = joinKeys(tokens(text)),
            ),
        )
    }

    suspend fun gistsOverlapping(from: Long, to: Long, limit: Int = 300): List<Gist> =
        dao().gistsOverlapping(from, to, limit).map { it.toGist() }

    suspend fun recentGists(limit: Int = 300): List<Gist> = dao().recentGists(limit).map { it.toGist() }.sortedBy { it.startTs }

    // ---------- 向量 ----------

    suspend fun vectors(kind: String, ids: Collection<Long>? = null): Map<Long, FloatArray> {
        val rows = if (ids == null) dao().vectors(kind) else ids.toList().chunked(500).flatMap { dao().vectorsFor(kind, it) }
        return rows.associate { it.refId to Vectors.unpack(it.vec) }
    }

    suspend fun hasVectors(): Boolean = dao().countVectors() > 0

    /** 只接受生成向量时输入文字没变的条目，避免把旧内容的向量写给新内容。 */
    suspend fun putVectors(kind: String, items: List<Pair<Long, FloatArray>>, inputs: Map<Long, String>, currentInput: suspend (Long) -> String?) {
        val accepted = items.mapNotNull { (id, vec) ->
            val now = currentInput(id) ?: return@mapNotNull null
            if (now != inputs[id]) return@mapNotNull null
            VectorEntity(kind, id, vec.size, Vectors.pack(vec), sha256(now))
        }
        if (accepted.isNotEmpty()) dao().putVectors(accepted)
    }

    suspend fun unindexedMemories(limit: Int): List<Mem> = dao().unindexedMemories(limit).map { it.toMem() }

    suspend fun unindexedEpisodes(limit: Int): List<Episode> = dao().unindexedEpisodes(limit).map { it.toEpisode() }

    suspend fun unindexedGists(limit: Int): List<Gist> = dao().unindexedGists(limit).map { it.toGist() }

    suspend fun deleteAllVectors() = dao().deleteAllVectors()

    // ---------- 状态 ----------

    suspend fun state(): StateEntity = dao().state() ?: StateEntity().also { dao().insertStateIfAbsent(it) }

    suspend fun setDigest(digest: String) = dao().setDigest(digest)

    suspend fun advanceCursor(upto: Long, increment: Int) = dao().advanceCursor(upto, increment)

    suspend fun setFailure(batchLimit: Int, failEpisode: Long, failCount: Int) = dao().setFailure(batchLimit, failEpisode, failCount)

    suspend fun setBatchLimit(batchLimit: Int) = dao().setBatchLimit(batchLimit)

    /** 清空全部记忆和原话。聊天记录在 chat.db 里，不受影响。 */
    suspend fun clearAll() {
        holder.bumpGeneration()
        transaction {
            val dao = dao()
            dao.deleteAllMemories()
            dao.deleteAllEpisodes()
            dao.deleteAllGists()
            dao.deleteAllLinks()
            dao.deleteAllVectors()
            dao.resetState()
            holder.get().useWriterConnection { connection ->
                connection.usePrepared("DELETE FROM ${FtsTables.EPISODE_FTS}") { it.step() }
            }
        }
    }
}
