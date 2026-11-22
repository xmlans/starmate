package org.xmyun.starmate.feature.memory.consolidate

import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import org.xmyun.starmate.feature.memory.model.Categories
import org.xmyun.starmate.feature.memory.model.Episode
import org.xmyun.starmate.feature.memory.model.Mem
import org.xmyun.starmate.feature.memory.model.Sources
import org.xmyun.starmate.feature.memory.retrieval.AUTO_LINKS
import org.xmyun.starmate.feature.memory.retrieval.AUTO_LINK_SIMILARITY
import org.xmyun.starmate.feature.memory.retrieval.DUPLICATE_SIMILARITY
import org.xmyun.starmate.feature.memory.retrieval.MemoryTime
import org.xmyun.starmate.feature.memory.text.clampEmotion
import org.xmyun.starmate.feature.memory.text.clampImportance
import org.xmyun.starmate.feature.memory.text.cleanContent
import org.xmyun.starmate.feature.memory.text.similarity

/** 新增一条记忆需要的字段。 */
data class NewMemory(
    val content: String,
    val category: String = Categories.OTHER,
    val importance: Int = 3,
    val pinned: Boolean = false,
    val tags: String = "",
    val source: String = Sources.USER,
    val eventAt: Long? = null,
    val episodeId: Long? = null,
    val emotion: Int = 0,
)

/** 修改记忆时要改的字段；null 表示不改。 */
data class MemPatch(
    val content: String? = null,
    val category: String? = null,
    val importance: Int? = null,
    val tags: String? = null,
    val emotion: Int? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    val hits: Int? = null,
    val eventAt: Long? = null,
)

/** 整理操作需要的存储能力。生产实现是 Room，测试里用内存实现。 */
interface MemoryOpsStore {
    suspend fun allMemories(): List<Mem>
    suspend fun memory(id: Long): Mem?
    suspend fun addMemory(memory: NewMemory, now: Long): Long?
    suspend fun updateMemory(id: Long, patch: MemPatch, now: Long): Boolean
    suspend fun deleteMemory(id: Long): Boolean
    /** 置顶的不归档。 */
    suspend fun archiveMemory(id: Long): Boolean
    /** 事实不再成立：标记失效时间并移入归档，保留历史。置顶的不失效。 */
    suspend fun invalidateMemory(id: Long, now: Long): Boolean
    /** 被想起的记忆得到强化；归档的被想起后重新激活（已失效的仍留在归档）。 */
    suspend fun touch(ids: Collection<Long>, now: Long)
    suspend fun link(a: Long, b: Long, weight: Double)
    suspend fun links(): Map<Long, List<Pair<Long, Double>>>
    /** 合并记忆时，把被合并条目的联想转给保留的那条。 */
    suspend fun relink(old: Long, new: Long)
    /** 事实反哺索引：从这轮对话里提炼出的记忆，也成为找到这轮原话的线索。 */
    suspend fun expandEpisodeKeys(episodeId: Long, text: String)
}

data class ApplyStats(
    var add: Int = 0,
    var update: Int = 0,
    var merge: Int = 0,
    var invalidate: Int = 0,
    var archive: Int = 0,
    var delete: Int = 0,
    var error: Int = 0,
) {
    val changed: Int get() = add + update + merge + invalidate + archive + delete
}

/** 一批对话的来源：轮号 → 原话。单用户私聊里说话人就是当事人。 */
class Provenance(batch: List<Episode> = emptyList()) {
    val byTurn: Map<Int, Episode> = batch.withIndex().associate { (i, e) -> i + 1 to e }
    fun episodeFor(op: JsonObject): Episode? = op.int("turn")?.let { byTurn[it] }
}

/**
 * 执行模型给出的操作。置顶记忆只允许 update，不会被删除、失效、归档或合并。
 * allowedIds 不为空时，只允许改动这些编号（压缩、反思时只给模型看了一部分）。
 * 每条操作单独捕获异常：某一条出错时跳过它，不让整批失败。
 */
class OperationsApplier(private val store: MemoryOpsStore, private val zone: ZoneId) {

    suspend fun apply(
        ops: List<JsonObject>,
        now: Long,
        provenance: Provenance = Provenance(),
        source: String? = null,
        maxImportance: Int = 5,
        allowedIds: Set<Long>? = null,
        errors: MutableList<String>? = null,
    ): ApplyStats {
        val stats = ApplyStats()
        var current = store.allMemories().associateBy { it.id }.toMutableMap()

        fun editable(id: Long?): Boolean = id != null && id in current && (allowedIds == null || id in allowedIds)

        suspend fun connect(memId: Long, op: JsonObject, auto: Boolean) {
            for (other in op.ids("related")) {
                if (other in current && other != memId) store.link(memId, other, 0.8)
            }
            if (auto) {
                val content = current[memId]?.content ?: return
                current.values.filter { it.id != memId && it.isValid }
                    .map { similarity(content, it.content) to it.id }
                    .sortedWith(compareByDescending<Pair<Double, Long>> { it.first }.thenByDescending { it.second })
                    .take(AUTO_LINKS)
                    .forEach { (sim, other) ->
                        if (sim >= AUTO_LINK_SIMILARITY && sim < DUPLICATE_SIMILARITY) store.link(memId, other, 0.4)
                    }
            }
        }

        suspend fun add(op: JsonObject): Boolean {
            val content = cleanContent(op.str("content"))
            if (content.isEmpty()) return false
            val importance = minOf(maxImportance, clampImportance(op.int("importance")))
            val evidence = provenance.episodeFor(op)
            val src = source ?: Sources.USER
            val eventAt = MemoryTime.parseDate(op.str("date"), zone)
            val dup = current.values.firstOrNull {
                it.isValid && it.content == content && it.source == src && it.eventAt == eventAt
            }
            if (dup != null) {
                // 重复的新信息说明这条记忆又被提起了：强化它，归档的重新激活
                store.touch(listOf(dup.id), now)
                store.memory(dup.id)?.let { current[dup.id] = it }
                return false
            }
            val category = op.str("category")?.takeIf { it in Categories.ALL } ?: Categories.OTHER
            val tags = op.text("tags") ?: ""
            val memId = store.addMemory(
                NewMemory(
                    content = content,
                    category = category,
                    importance = importance,
                    tags = tags,
                    source = src,
                    eventAt = eventAt,
                    episodeId = evidence?.id,
                    emotion = clampEmotion(op.int("emotion")),
                ),
                now,
            ) ?: return false
            if (evidence != null) store.expandEpisodeKeys(evidence.id, "$content $tags")
            store.memory(memId)?.let { current[memId] = it }
            connect(memId, op, auto = true)
            return true
        }

        suspend fun update(op: JsonObject): Boolean {
            val memId = op.id("id")
            if (!editable(memId)) return false
            val existing = current.getValue(memId!!)
            if (!existing.isValid) return false
            val content = if ("content" in op) cleanContent(op.str("content")) else existing.content
            if (content.isEmpty()) return false
            val ok = store.updateMemory(
                memId,
                MemPatch(
                    content = content,
                    importance = op.int("importance")?.let { minOf(maxImportance, clampImportance(it)) },
                    category = op.str("category")?.takeIf { it in Categories.ALL },
                    tags = op.text("tags")?.takeIf { it.isNotBlank() },
                    eventAt = MemoryTime.parseDate(op.str("date"), zone),
                    archived = false,
                    emotion = op.int("emotion")?.let(::clampEmotion),
                ),
                now,
            )
            if (!ok) return false
            store.memory(memId)?.let { current[memId] = it }
            connect(memId, op, auto = false)
            return true
        }

        suspend fun merge(op: JsonObject): Boolean {
            val ids = op.ids("ids").distinct()
            val content = cleanContent(op.str("content"))
            if (ids.size < 2 || content.isEmpty() || ids.any { !editable(it) }) return false
            val group = ids.map { current.getValue(it) }
            if (group.any { it.pinned || !it.isValid }) return false
            // 合并后继承最高重要度、累计使用次数和最高情绪分量，避免老记忆因合并而“变弱”
            val importance = op.int("importance") ?: group.maxOf { it.importance }
            store.updateMemory(
                ids[0],
                MemPatch(
                    content = content,
                    importance = minOf(maxImportance, clampImportance(importance)),
                    tags = op.text("tags")?.takeIf { it.isNotBlank() }
                        ?: group.mapNotNull { it.tags.takeIf(String::isNotBlank) }.joinToString(" "),
                    hits = group.sumOf { it.hits },
                    archived = false,
                    eventAt = MemoryTime.parseDate(op.str("date"), zone) ?: group[0].eventAt,
                    emotion = group.maxOf { it.emotion },
                ),
                now,
            )
            for (other in ids.drop(1)) {
                store.relink(other, ids[0])
                store.deleteMemory(other)
                current.remove(other)
            }
            store.memory(ids[0])?.let { current[ids[0]] = it }
            return true
        }

        suspend fun retire(op: JsonObject, kind: String): Boolean {
            val memId = op.id("id")
            if (!editable(memId) || current.getValue(memId!!).pinned) return false
            val done = when (kind) {
                "invalidate" -> store.invalidateMemory(memId, now)
                "archive" -> store.archiveMemory(memId)
                else -> store.deleteMemory(memId)
            }
            if (!done) return false
            if (kind == "delete") current.remove(memId) else store.memory(memId)?.let { current[memId] = it }
            return true
        }

        for (op in ops) {
            val kind = op.str("op")?.lowercase() ?: continue
            try {
                val done = when (kind) {
                    "add" -> add(op)
                    "update" -> update(op)
                    "merge" -> merge(op)
                    "invalidate", "archive", "delete" -> retire(op, kind)
                    else -> continue
                }
                if (done) {
                    when (kind) {
                        "add" -> stats.add++
                        "update" -> stats.update++
                        "merge" -> stats.merge++
                        "invalidate" -> stats.invalidate++
                        "archive" -> stats.archive++
                        "delete" -> stats.delete++
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stats.error++
                errors?.add("$kind: ${e.javaClass.simpleName}: ${e.message}")
                current = store.allMemories().associateBy { it.id }.toMutableMap()
            }
        }
        return stats
    }
}

// ---------- 容量管理 ----------

fun weakest(memories: List<Mem>, now: Long, halfLife: Double): List<Mem> =
    memories.filter { !it.pinned }.sortedWith(compareBy<Mem> { it.strength(now, halfLife) }.thenBy { it.id })

/** 兜底：把强度最低的活跃记忆归档，直到不超过上限。 */
suspend fun archiveOverflow(store: MemoryOpsStore, limit: Int, now: Long, halfLife: Double): List<Mem> {
    val active = store.allMemories().filter { !it.archived }
    val victims = weakest(active, now, halfLife).take(maxOf(0, active.size - limit))
    victims.forEach { store.archiveMemory(it.id) }
    return victims
}

/** 归档也满了才真正遗忘：删掉最弱的（已失效的旧事实最先被忘）。原话不受影响。 */
suspend fun trimArchive(store: MemoryOpsStore, limit: Int, now: Long, halfLife: Double): List<Mem> {
    val archived = store.allMemories().filter { it.archived }
    val victims = weakest(archived, now, halfLife).take(maxOf(0, archived.size - limit))
    victims.forEach { store.deleteMemory(it.id) }
    return victims
}

/** 低重要度且早已淡忘的琐碎记忆移入归档。 */
suspend fun archiveFaded(store: MemoryOpsStore, now: Long, halfLife: Double, threshold: Double = 0.05): List<Mem> {
    val victims = store.allMemories().filter {
        !it.archived && !it.pinned && it.importance <= 2 && it.decay(now, halfLife) < threshold
    }
    victims.forEach { store.archiveMemory(it.id) }
    return victims
}
