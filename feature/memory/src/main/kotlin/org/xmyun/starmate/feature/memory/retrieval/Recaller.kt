package org.xmyun.starmate.feature.memory.retrieval

import javax.inject.Inject
import javax.inject.Singleton
import org.xmyun.starmate.feature.memory.data.MemoryRepository
import org.xmyun.starmate.feature.memory.inject.MemoryRender
import org.xmyun.starmate.feature.memory.inject.RecallPick
import org.xmyun.starmate.feature.memory.model.Episode
import org.xmyun.starmate.feature.memory.model.Gist
import org.xmyun.starmate.feature.memory.model.Mem
import org.xmyun.starmate.feature.memory.model.MemorySettings
import org.xmyun.starmate.feature.memory.model.Span

/** 检索的编排：关键词（FTS5 + LIKE）+ 向量 + 时间范围，交给 [MemoryRender.selectRecall] 挑选。 */
@Singleton
class Recaller @Inject constructor(
    private val repo: MemoryRepository,
    private val vectors: VectorIndex,
    private val settings: MemorySettings,
) {
    class EpisodeSearch(val relevance: Relevance, val excerpts: List<Episode>)

    suspend fun embedQuery(text: String?): FloatArray? = vectors.embedQuery(text)

    suspend fun memoryRelevance(memories: List<Mem>, query: String, context: String, qvec: FloatArray?): Relevance {
        val lexical = Lexical.ofMemories(memories)
        val lex = lexical.scores(lexical.query(query, context))
        val cos = qvec?.let { Vectors.cosineMany(it, repo.vectors("m")).takeIf { c -> c.isNotEmpty() } }
        return Relevance(hybrid(lex, cos, memories.map { it.id }), lex, cos)
    }

    /**
     * 在原话里检索：先用关键词、时间范围和向量找出候选，再按词项 IDF + 向量打分。
     * gate 为真时只保留够格被想起的候选，门槛和时间加成与 selectRecall 一致。
     */
    suspend fun searchEpisodes(
        query: String,
        qvec: FloatArray?,
        span: Span?,
        exclude: Set<Long>,
        top: Int,
        gate: Boolean = true,
    ): EpisodeSearch {
        val candidates = LinkedHashSet<Long>()
        candidates += repo.keywordCandidates(query, CANDIDATE_LIMIT)
        if (span != null) repo.episodesInSpan(span.start, span.end, CANDIDATE_LIMIT).forEach { candidates += it.id }
        var cos: Map<Long, Double>? = null
        if (qvec != null) {
            val start = repo.windowStart(VECTOR_WINDOW)
            val pool = repo.vectors("e").filterKeys { it >= start }
            cos = Vectors.cosineMany(qvec, pool).takeIf { it.isNotEmpty() }
            cos?.entries?.sortedByDescending { it.value }?.take(VECTOR_CANDIDATES)?.forEach { if (it.value >= 0.45) candidates += it.key }
        }
        candidates.removeAll(exclude)
        if (candidates.isEmpty()) return EpisodeSearch(Relevance.EMPTY, emptyList())
        val episodes = repo.episodesByIds(candidates).associateBy { it.id }
        val corpus = minOf(repo.countEpisodes(), settings.episodeSearchWindow)
        val lex = keysScores(episodes.mapValues { it.value.keys }, query, corpus)
        val dense = cos?.filterKeys { it in episodes }
        val rel = hybrid(lex, dense, episodes.keys)
        val scored = episodes.values.mapNotNull { e ->
            var score = rel[e.id] ?: 0.0
            if (span != null) score = if (e.ts in span) score + 0.4 else score * 0.5
            val keep = if (gate) {
                recallable(score, lex[e.id]?.hits ?: 0, dense?.get(e.id)) || (span != null && e.ts in span && score > 0.15)
            } else {
                score > 0
            }
            if (keep) score to e else null
        }.sortedWith(compareByDescending<Pair<Double, Episode>> { it.first }.thenByDescending { it.second.id })
        return EpisodeSearch(Relevance(rel, lex, dense), scored.take(maxOf(1, top)).map { it.second }.sortedBy { it.id })
    }

    suspend fun gistRelevance(gists: List<Gist>, query: String, qvec: FloatArray?): Map<Long, Double> {
        if (gists.isEmpty()) return emptyMap()
        val lexical = Lexical.ofGists(gists)
        val lex = lexical.scores(lexical.query(query))
        val cos = qvec?.let { q -> Vectors.cosineMany(q, repo.vectors("g", gists.map { it.id })).takeIf { it.isNotEmpty() } }
        return hybrid(lex, cos, gists.map { it.id })
    }

    /**
     * 回想：相关记忆 + 联想到的记忆；提到过去（或工具主动回想）时再加那段时间的梗概和原话。
     * excludeEpisodes 是本轮上下文里已有的原话，不再重复回想。
     */
    suspend fun recall(
        render: MemoryRender,
        memories: List<Mem>,
        query: String,
        context: String,
        qvec: FloatArray?,
        now: Long,
        exclude: Set<Long>,
        budget: Int,
        maxEpisodes: Int,
        alwaysEpisodes: Boolean,
        excludeEpisodes: Set<Long> = emptySet(),
        mem: Relevance? = null,
    ): RecallPick {
        val memRel = mem ?: memoryRelevance(memories, query, context, qvec)
        val span = TimeExpressionParser.parse(query, now, render.zone)
        var episodes = emptyList<Episode>()
        var gists = emptyList<Gist>()
        var ep = Relevance.EMPTY
        var gistRel = emptyMap<Long, Double>()
        if (maxEpisodes > 0 && (alwaysEpisodes || wantsEpisodes(query, span))) {
            val search = searchEpisodes(query, qvec, span, excludeEpisodes, maxEpisodes * EPISODE_PRESELECT)
            ep = search.relevance
            episodes = search.excerpts
            gists = if (span != null) repo.gistsOverlapping(span.start, span.end) else repo.recentGists()
            gistRel = gistRelevance(gists, query, qvec)
        }
        return render.selectRecall(
            memories, memRel.rel, memRel.lex, memRel.cos, episodes, ep.rel, ep.lex, ep.cos, span,
            exclude, budget, maxEpisodes, links = repo.links(), gists = gists, gistRel = gistRel,
        )
    }

    companion object {
        const val CANDIDATE_LIMIT = 500
        const val VECTOR_WINDOW = 1500
        const val VECTOR_CANDIDATES = 200
        const val EPISODE_PRESELECT = 4
    }
}
