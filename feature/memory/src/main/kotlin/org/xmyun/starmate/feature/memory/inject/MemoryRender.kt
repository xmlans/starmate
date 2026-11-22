package org.xmyun.starmate.feature.memory.inject

import java.time.ZoneId
import java.time.format.TextStyle
import kotlin.math.abs
import org.xmyun.starmate.feature.memory.model.Categories
import org.xmyun.starmate.feature.memory.model.DAY_MS
import org.xmyun.starmate.feature.memory.model.Episode
import org.xmyun.starmate.feature.memory.model.Gist
import org.xmyun.starmate.feature.memory.model.Mem
import org.xmyun.starmate.feature.memory.model.Span
import org.xmyun.starmate.feature.memory.prompt.PromptTexts
import org.xmyun.starmate.feature.memory.retrieval.Lex
import org.xmyun.starmate.feature.memory.retrieval.MemoryTime
import org.xmyun.starmate.feature.memory.retrieval.recallable
import org.xmyun.starmate.feature.memory.retrieval.spread
import org.xmyun.starmate.feature.memory.text.estimateTokens
import org.xmyun.starmate.feature.memory.text.excerpt

/** 很久没被想起、又只提过一两次的事，回想时标“印象模糊”。 */
const val FUZZY_AFTER_DAYS = 120

data class RecallPick(val memories: List<Mem>, val episodes: List<Episode>, val gists: List<Gist>) {
    val isEmpty: Boolean get() = memories.isEmpty() && episodes.isEmpty() && gists.isEmpty()
}

/** 所有给模型看的记忆文字都在这里渲染。纯函数，不碰数据库。 */
class MemoryRender(val texts: PromptTexts, val zone: ZoneId) {

    fun date(ts: Long?, withTime: Boolean = false): String = MemoryTime.fmtDate(ts, zone, withTime)

    fun isFuzzy(m: Mem, now: Long): Boolean {
        val last = maxOf(m.createdAt, m.lastUsedAt)
        return (m.archived && m.isValid) || (now - last > FUZZY_AFTER_DAYS * DAY_MS && m.hits < 2)
    }

    /** 快照里只用不随时间变化的标记（保持逐字稳定）；回想时（传了 now）再加“印象模糊”。 */
    fun renderLine(m: Mem, now: Long? = null): String {
        val date = if (m.eventAt != null) "（${date(m.eventAt)}）" else ""
        val marks = if (now != null && m.isValid && isFuzzy(m, now)) texts["mark_fuzzy"] else ""
        return "- $date${m.content}$marks"
    }

    fun lineCost(m: Mem): Int = estimateTokens(renderLine(m)) + 1

    // ---------- 稳定快照 ----------

    private fun snapshotOrder(now: Long, halfLife: Double) =
        compareByDescending<Mem> { it.isCore }.thenByDescending { it.strength(now, halfLife) }.thenBy { it.id }

    /** 核心记忆（置顶 / 重要度 5）优先，其余按强度从高到低，直到预算用完。 */
    fun selectSnapshot(memories: List<Mem>, now: Long, budget: Int, halfLife: Double): List<Mem> {
        val pool = memories.filter { !it.archived && it.isValid }.sortedWith(snapshotOrder(now, halfLife))
        val chosen = ArrayList<Mem>()
        var used = 0
        for (m in pool) {
            val cost = lineCost(m)
            if (used + cost > budget) continue
            chosen += m
            used += cost
        }
        return chosen
    }

    fun renderSnapshot(chosen: List<Mem>, digest: String): String {
        if (chosen.isEmpty() && digest.isEmpty()) return ""
        val lines = mutableListOf(texts["snapshot_header"])
        if (digest.isNotEmpty()) lines += texts["digest_label"] + digest
        val groups = chosen.groupBy { it.category }
        for (category in Categories.ALL) {
            val group = groups[category] ?: continue
            lines += "【${texts.categoryTitle(category)}】"
            // 按 id 排序，同样的记忆集合永远渲染成同样的文字
            group.sortedBy { it.id }.forEach { lines += renderLine(it) }
        }
        return lines.joinToString("\n")
    }

    /** 保证整段文字不超过预算：超了就从最弱的一条开始去掉。 */
    fun fitSnapshot(chosen: List<Mem>, digest: String, budget: Int, now: Long, halfLife: Double): Pair<String, List<Mem>> {
        val sorted = chosen.sortedWith(snapshotOrder(now, halfLife)).toMutableList()
        var block = renderSnapshot(sorted, digest)
        while (sorted.isNotEmpty() && estimateTokens(block) > budget) {
            sorted.removeAt(sorted.lastIndex)
            block = renderSnapshot(sorted, digest)
        }
        if (estimateTokens(block) > budget) return "" to emptyList()
        return block to sorted
    }

    // ---------- 动态回想 ----------

    fun renderEpisode(e: Episode, limit: Int = 2000): String = texts.fmt(
        "episode_line",
        "id" to e.id,
        "time" to date(e.ts, true),
        "user" to excerpt(e.userText, limit, texts["episode_omitted"]),
        "reply" to excerpt(e.replyText, limit, texts["episode_omitted"]),
    )

    fun renderGist(g: Gist): String {
        val start = date(g.startTs)
        val end = date(g.endTs)
        return texts.fmt("gist_line", "range" to if (start == end) start else "$start~$end", "summary" to g.summary)
    }

    fun renderRecall(pick: RecallPick, now: Long): String {
        if (pick.isEmpty) return ""
        val lines = mutableListOf(texts["recall_header"])
        for (m in pick.memories) {
            val state = if (!m.isValid) texts.fmt("mark_outdated", "date" to date(m.invalidAt)) else ""
            lines += renderLine(m, now) + state
        }
        if (pick.gists.isNotEmpty()) {
            lines += texts["recall_gists"]
            pick.gists.forEach { lines += renderGist(it) }
        }
        if (pick.episodes.isNotEmpty()) {
            lines += texts["recall_episodes"]
            pick.episodes.forEach { lines += renderEpisode(it) }
        }
        lines += texts["recall_footer"]
        return lines.joinToString("\n")
    }

    /** 快照里没有、但和当前消息高度相关的记忆（含联想到的），以及（提到过去时）那段时间的梗概和原话。 */
    fun selectRecall(
        memories: List<Mem>,
        memRel: Map<Long, Double>,
        memLex: Map<Long, Lex>,
        memCos: Map<Long, Double>?,
        episodes: List<Episode>,
        epRel: Map<Long, Double>,
        epLex: Map<Long, Lex>,
        epCos: Map<Long, Double>?,
        span: Span?,
        exclude: Set<Long>,
        budget: Int,
        maxEpisodes: Int,
        links: Map<Long, List<Pair<Long, Double>>>? = null,
        gists: List<Gist> = emptyList(),
        gistRel: Map<Long, Double> = emptyMap(),
        maxGists: Int = 3,
    ): RecallPick {
        val (spreadRel, assoc) = spread(memRel, links)
        fun boosted(rel: Double, ts: Long?): Double = when {
            span == null -> rel
            ts in span -> rel + 0.4
            else -> rel * 0.5
        }

        val memPool = memories.mapNotNull { m ->
            if (m.id in exclude) return@mapNotNull null
            val rel = boosted(spreadRel[m.id] ?: 0.0, m.eventAt ?: m.createdAt)
            val hits = memLex[m.id]?.hits ?: 0
            val ok = recallable(rel, hits, memCos?.get(m.id)) || m.id in assoc ||
                (span != null && m.eventAt in span && rel > 0.15)
            if (ok) rel to m else null
        }.sortedByDescending { it.first }
        val epPool = episodes.mapNotNull { e ->
            val rel = boosted(epRel[e.id] ?: 0.0, e.ts)
            val hits = epLex[e.id]?.hits ?: 0
            val ok = recallable(rel, hits, epCos?.get(e.id)) || (span != null && e.ts in span && rel > 0.15)
            if (ok) rel to e else null
        }.sortedByDescending { it.first }
        val gistPool = gists.mapNotNull { g ->
            val rel = gistRel[g.id] ?: 0.0
            val overlap = span != null && span.overlaps(g.startTs, g.endTs)
            if (rel >= 0.3 || overlap) (rel + if (overlap) 0.4 else 0.0) to g else null
        }.sortedByDescending { it.first }

        val chosenM = ArrayList<Mem>()
        val chosenE = ArrayList<Episode>()
        val chosenG = ArrayList<Gist>()
        var used = estimateTokens(texts["recall_header"] + texts["recall_footer"]) + 8
        for ((_, m) in memPool) {
            val cost = lineCost(m) + 12
            if (used + cost <= budget) {
                chosenM += m
                used += cost
            }
        }
        for ((_, g) in gistPool.take(maxGists)) {
            val cost = estimateTokens(renderGist(g)) + 4
            if (used + cost <= budget) {
                chosenG += g
                used += cost
            }
        }
        for ((_, e) in epPool.take(maxEpisodes)) {
            val cost = estimateTokens(renderEpisode(e)) + 2
            if (used + cost <= budget) {
                chosenE += e
                used += cost
            }
        }
        return RecallPick(chosenM, chosenE.sortedBy { it.id }, chosenG.sortedBy { it.startTs })
    }

    // ---------- 顺带关心 ----------

    fun relativeDay(ts: Long, now: Long): String {
        val days = MemoryTime.dayOffset(ts, now, zone)
        return when {
            days in -2..2 -> texts["day_$days"]
            days < 0 -> texts.fmt("days_ago", "n" to -days)
            else -> texts.fmt("days_later", "n" to days)
        }
    }

    fun renderCare(memories: List<Mem>, now: Long): String {
        if (memories.isEmpty()) return ""
        val lines = mutableListOf(texts["care_header"])
        memories.forEach { m ->
            lines += texts.fmt("care_line", "rel" to relativeDay(m.eventAt!!, now), "date" to date(m.eventAt), "content" to m.content)
        }
        lines += texts["care_footer"]
        return lines.joinToString("\n")
    }

    // ---------- 整理、压缩、反思的输入 ----------

    fun formatMemories(memories: List<Mem>): String {
        if (memories.isEmpty()) return texts["none"]
        return memories.joinToString("\n") { m ->
            val flags = (if (m.pinned) " 📌" else "") + (if (m.archived && m.isValid) " 🗄" else "") +
                (if (!m.isValid) " ⌛" else "") + (if (m.emotion > 0) " emo${m.emotion}" else "")
            val date = if (m.eventAt != null) " (${date(m.eventAt)})" else ""
            "#${m.id} [${m.category}|${m.importance}]$flags$date ${m.content}"
        }
    }

    fun formatBatch(batch: List<Episode>, excerptChars: Int): String =
        batch.mapIndexed { i, e ->
            texts.fmt(
                "batch_line",
                "n" to i + 1,
                "time" to date(e.ts, true),
                "user" to excerpt(e.userText, excerptChars, texts["episode_omitted"]),
                "reply" to excerpt(e.replyText, excerptChars, texts["episode_omitted"]),
            )
        }.joinToString("\n")

    fun today(now: Long): String {
        val d = MemoryTime.localDate(now, zone)
        return "${date(now)} ${d.dayOfWeek.getDisplayName(TextStyle.FULL, texts.locale)}"
    }

    fun buildConsolidatePrompt(related: List<Mem>, batch: List<Episode>, now: Long, excerptChars: Int): String =
        texts.fmt("consolidate_today", "today" to today(now)) + "\n\n" +
            texts["related_label"] + "\n" + formatMemories(related) + "\n\n" +
            texts["new_dialog_label"] + "\n" + formatBatch(batch, excerptChars)

    fun buildCompactPrompt(candidates: List<Mem>, digest: String): String =
        texts["digest_section"] + "\n" + digest.ifEmpty { texts["none"] } + "\n\n" +
            texts["candidates_section"] + "\n" + formatMemories(candidates)

    fun buildReflectPrompt(memories: List<Mem>, digest: String): String =
        texts["digest_section"] + "\n" + digest.ifEmpty { texts["none"] } + "\n\n" +
            texts["memories_section"] + "\n" + formatMemories(memories)
}

/** 快到了或刚过去的事：人会主动问一句。关心过的 3 天内不再提。 */
fun dueReminders(
    memories: List<Mem>,
    now: Long,
    aheadDays: Double = 1.5,
    behindDays: Double = 2.5,
    renudgeDays: Double = 3.0,
    limit: Int = 3,
): List<Mem> {
    val categories = setOf(Categories.EVENT, Categories.SELF, Categories.FRAGMENT, Categories.RELATION, Categories.FACT)
    return memories.filter { m ->
        val at = m.eventAt
        m.isValid && at != null && m.category in categories &&
            at >= now - (behindDays * DAY_MS).toLong() && at <= now + (aheadDays * DAY_MS).toLong() &&
            (m.nudgedAt == null || now - m.nudgedAt >= renudgeDays * DAY_MS)
    }.sortedWith(compareBy<Mem> { abs(it.eventAt!! - now) }.thenByDescending { it.importance })
        .take(limit)
}

/** 整理时只给模型看核心记忆、未处理的碎片和与新对话相关的记忆，提示词不随记忆增多而膨胀。 */
fun relatedForConsolidation(
    render: MemoryRender,
    memories: List<Mem>,
    rel: Map<Long, Double>,
    budget: Int,
    now: Long,
    halfLife: Double,
): List<Mem> {
    val scored = memories.mapNotNull { m ->
        val r = rel[m.id] ?: 0.0
        val score = when {
            m.isCore -> 3.0
            m.category == Categories.FRAGMENT && !m.archived -> 2.0
            r > 0.1 -> r + 0.1 * minOf(1.0, m.strength(now, halfLife))
            else -> return@mapNotNull null
        }
        score to m
    }.sortedWith(compareByDescending<Pair<Double, Mem>> { it.first }.thenBy { it.second.id })
    val chosen = ArrayList<Mem>()
    var used = 0
    for ((_, m) in scored) {
        val cost = render.lineCost(m) + 8
        if (used + cost > budget) continue
        chosen += m
        used += cost
    }
    return chosen.sortedBy { it.id }
}
