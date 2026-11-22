package org.xmyun.starmate.feature.memory

import org.xmyun.starmate.feature.memory.consolidate.MemPatch
import org.xmyun.starmate.feature.memory.consolidate.MemoryOpsStore
import org.xmyun.starmate.feature.memory.consolidate.NewMemory
import org.xmyun.starmate.feature.memory.model.Episode
import org.xmyun.starmate.feature.memory.model.Mem
import org.xmyun.starmate.feature.memory.text.cleanContent
import org.xmyun.starmate.feature.memory.text.joinKeys
import org.xmyun.starmate.feature.memory.text.tokens

/** 纯内存实现，语义与 Room 实现一致，用来不依赖 Android 地测试整理操作和遗忘。 */
class InMemoryOpsStore : MemoryOpsStore {
    val memories = linkedMapOf<Long, Mem>()
    val linkMap = mutableMapOf<Pair<Long, Long>, Double>()
    val episodes = linkedMapOf<Long, Episode>()
    private var nextId = 1L
    private var nextEpisode = 1L

    fun add(content: String, now: Long = 0, transform: (Mem) -> Mem = { it }): Long {
        val id = nextId++
        memories[id] = transform(Mem(id = id, content = content, createdAt = now, updatedAt = now, lastUsedAt = now))
        return id
    }

    fun addEpisode(user: String, reply: String, ts: Long = 0): Episode {
        val e = Episode(nextEpisode++, ts, user, reply, joinKeys(tokens("$user $reply")))
        episodes[e.id] = e
        return e
    }

    override suspend fun allMemories(): List<Mem> = memories.values.toList()
    override suspend fun memory(id: Long): Mem? = memories[id]

    override suspend fun addMemory(memory: NewMemory, now: Long): Long? {
        val content = cleanContent(memory.content)
        if (content.isEmpty()) return null
        val id = nextId++
        memories[id] = Mem(
            id = id, content = content, category = memory.category, importance = memory.importance.coerceIn(1, 5),
            pinned = memory.pinned, createdAt = now, updatedAt = now, lastUsedAt = now, tags = memory.tags,
            source = memory.source, eventAt = memory.eventAt, episodeId = memory.episodeId, emotion = memory.emotion,
        )
        return id
    }

    override suspend fun updateMemory(id: Long, patch: MemPatch, now: Long): Boolean {
        val old = memories[id] ?: return false
        memories[id] = old.copy(
            content = patch.content ?: old.content,
            category = patch.category ?: old.category,
            importance = patch.importance ?: old.importance,
            tags = patch.tags ?: old.tags,
            emotion = patch.emotion ?: old.emotion,
            pinned = patch.pinned ?: old.pinned,
            archived = patch.archived ?: old.archived,
            hits = patch.hits ?: old.hits,
            eventAt = patch.eventAt ?: old.eventAt,
            updatedAt = now,
        )
        return true
    }

    override suspend fun deleteMemory(id: Long): Boolean {
        val removed = memories.remove(id) != null
        if (removed) linkMap.keys.removeAll { it.first == id || it.second == id }
        return removed
    }

    override suspend fun archiveMemory(id: Long): Boolean {
        val m = memories[id] ?: return false
        if (m.pinned) return false
        memories[id] = m.copy(archived = true)
        return true
    }

    override suspend fun invalidateMemory(id: Long, now: Long): Boolean {
        val m = memories[id] ?: return false
        if (m.pinned || m.invalidAt != null) return false
        memories[id] = m.copy(invalidAt = now, archived = true, updatedAt = now)
        return true
    }

    override suspend fun touch(ids: Collection<Long>, now: Long) {
        for (id in ids) {
            val m = memories[id] ?: continue
            memories[id] = m.copy(lastUsedAt = now, hits = m.hits + 1, archived = if (m.invalidAt == null) false else m.archived)
        }
    }

    override suspend fun link(a: Long, b: Long, weight: Double) {
        if (a == b) return
        val key = minOf(a, b) to maxOf(a, b)
        linkMap[key] = maxOf(linkMap[key] ?: 0.0, weight)
    }

    override suspend fun links(): Map<Long, List<Pair<Long, Double>>> {
        val result = mutableMapOf<Long, MutableList<Pair<Long, Double>>>()
        for ((key, w) in linkMap) {
            result.getOrPut(key.first) { mutableListOf() } += key.second to w
            result.getOrPut(key.second) { mutableListOf() } += key.first to w
        }
        return result
    }

    override suspend fun relink(old: Long, new: Long) {
        for ((key, w) in linkMap.toMap()) {
            if (key.first == old || key.second == old) {
                val other = if (key.first == old) key.second else key.first
                if (other != new) link(new, other, w)
                linkMap.remove(key)
            }
        }
    }

    override suspend fun expandEpisodeKeys(episodeId: Long, text: String) {
        val e = episodes[episodeId] ?: return
        episodes[episodeId] = e.copy(keys = joinKeys(e.keys.split(' ').filter { it.isNotEmpty() }.toSet() + tokens(text)))
    }
}
