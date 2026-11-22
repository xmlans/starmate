package org.xmyun.starmate.feature.memory.consolidate

import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.feature.memory.data.MemoryRepository
import org.xmyun.starmate.feature.memory.inject.MemoryRender
import org.xmyun.starmate.feature.memory.model.Mem
import org.xmyun.starmate.feature.memory.model.MemorySettings
import org.xmyun.starmate.feature.memory.prompt.PromptSource
import org.xmyun.starmate.feature.memory.retrieval.Lexical
import org.xmyun.starmate.feature.memory.text.estimateTokens

/** 给 [MemoryRender] 提供当前语言的文字和时区。 */
@Singleton
class RenderSource @Inject constructor(private val prompts: PromptSource, private val clock: Clock) {
    fun render(): MemoryRender = MemoryRender(prompts.texts(), clock.zone)
}

/**
 * 遗忘与容量：淡忘的琐事归档；活跃记忆超过上限时让模型合并压缩、要点进概况，
 * 模型不可用时按强度归档；归档也满了才真正遗忘。
 */
@Singleton
class Archiver @Inject constructor(
    private val repo: MemoryRepository,
    private val llm: MemoryLlm,
    private val renders: RenderSource,
    private val settings: MemorySettings,
    private val diag: DiagLog,
) {
    suspend fun keepInBudget(now: Long) {
        val half = settings.halfLifeDays
        archiveFaded(repo, now, half)
        val active = repo.allMemories().filter { !it.archived }
        if (active.size > settings.maxActive) {
            // 压到 90% 留出余量，避免每次整理都触发压缩
            val target = maxOf(1, (settings.maxActive * 0.9).toInt())
            val need = active.size - target
            val render = renders.render()
            val candidates = compactCandidates(active, need, now)
            val system = render.texts.fmt("compact_system", "need" to need, "digest_chars" to settings.digestMaxChars)
            val generation = repo.holder.generation
            when (val result = llm.call(system, render.buildCompactPrompt(candidates, repo.state().digest))) {
                is LlmOutcome.Ok -> if (repo.holder.generation == generation) {
                    val data = result.data
                    if (data != null) {
                        // 只允许动给它看过的候选，防止误伤其他记忆
                        val ops = data.ops().filter { it.str("op") != "add" }
                        val errors = mutableListOf<String>()
                        OperationsApplier(repo, render.zone).apply(ops, now, allowedIds = candidates.map { it.id }.toSet(), errors = errors)
                        errors.take(5).forEach { diag.log(TAG, "compaction skipped an operation: $it") }
                        data.str("digest")?.trim()?.takeIf { it.isNotEmpty() }?.let { repo.setDigest(it.take(settings.digestMaxChars)) }
                    }
                }
                LlmOutcome.Budget -> diag.log(TAG, "compaction skipped: daily budget used up, archiving by strength")
                is LlmOutcome.Error -> diag.log(TAG, "compaction failed (${result.reason}), archiving by strength")
            }
            val moved = archiveOverflow(repo, target, now, half)
            if (moved.isNotEmpty()) diag.log(TAG, "archived ${moved.size} memories by strength")
        }
        val forgotten = trimArchive(repo, settings.maxArchive, now, half)
        if (forgotten.isNotEmpty()) diag.log(TAG, "archive full, forgot ${forgotten.size} weakest memories")
    }

    /** 最弱的一批记忆，加上和它们相似的条目（方便合并），受 token 预算限制。 */
    fun compactCandidates(active: List<Mem>, need: Int, now: Long): List<Mem> {
        val pool = weakest(active, now, settings.halfLifeDays)
        val seeds = pool.take(need * 2 + 20)
        val seedIds = seeds.map { it.id }.toSet()
        val lexical = Lexical.ofMemories(pool)
        val scores = lexical.scores(lexical.query(seeds.take(need).joinToString(" ") { it.content }))
        val similar = pool.filter { it.id !in seedIds }.sortedByDescending { scores[it.id]?.score ?: 0.0 }.take(need)
        val chosen = ArrayList<Mem>()
        var used = 0
        for (m in seeds + similar) {
            val cost = estimateTokens(m.content) + 12
            if (used + cost > settings.consolidateContextTokens * 2) break
            chosen += m
            used += cost
        }
        return chosen.sortedBy { it.id }
    }

    companion object {
        private const val TAG = "memory"
    }
}
