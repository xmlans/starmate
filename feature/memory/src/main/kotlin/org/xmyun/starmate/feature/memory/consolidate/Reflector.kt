package org.xmyun.starmate.feature.memory.consolidate

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.feature.memory.data.MemoryRepository
import org.xmyun.starmate.feature.memory.model.Categories
import org.xmyun.starmate.feature.memory.model.MemorySettings
import org.xmyun.starmate.feature.memory.model.Sources
import org.xmyun.starmate.feature.memory.text.clampImportance
import org.xmyun.starmate.feature.memory.text.estimateTokens

/** 像人安静时回想一样，从零散记忆里归纳出印象（每条至少 2 条依据），并刷新长期概况。 */
@Singleton
class Reflector @Inject constructor(
    private val repo: MemoryRepository,
    private val llm: MemoryLlm,
    private val renders: RenderSource,
    private val vectors: org.xmyun.starmate.feature.memory.retrieval.VectorIndex,
    private val settings: MemorySettings,
    private val diag: DiagLog,
) {
    sealed interface Result {
        data class Done(val stats: ApplyStats) : Result
        data object TooFew : Result
        data object Budget : Result
        data object Failed : Result
    }

    suspend fun reflect(now: Long): Result {
        val half = settings.halfLifeDays
        val memories = repo.allMemories().filter { !it.archived && it.isValid }
        if (memories.size < 3) return Result.TooFew
        val insights = memories.filter { it.category == Categories.INSIGHT }
        val others = memories.filter { it.category != Categories.INSIGHT }
            .sortedWith(compareByDescending<org.xmyun.starmate.feature.memory.model.Mem> { it.isCore }.thenByDescending { it.strength(now, half) })
        val pool = insights.toMutableList()
        var used = insights.sumOf { estimateTokens(it.content) + 12 }
        for (m in others) {
            val cost = estimateTokens(m.content) + 12
            if (used + cost > settings.consolidateContextTokens) break
            pool += m
            used += cost
        }
        pool.sortBy { it.id }
        val render = renders.render()
        val system = render.texts.fmt("reflect_system", "max_insights" to settings.maxInsights, "digest_chars" to settings.digestMaxChars)
        val generation = repo.holder.generation
        val data = when (val result = llm.call(system, render.buildReflectPrompt(pool, repo.state().digest))) {
            is LlmOutcome.Ok -> result.data
            LlmOutcome.Budget -> return Result.Budget
            is LlmOutcome.Error -> {
                diag.log(TAG, "reflection failed: ${result.reason}")
                return Result.Failed
            }
        }
        if (repo.holder.generation != generation || !OperationsParser.validOperations(data)) return Result.Failed
        val poolIds = pool.map { it.id }.toSet()
        val ops = data!!.ops().mapNotNull { op ->
            if (op.str("op") != "add") return@mapNotNull op
            // 印象必须有至少两条真实记忆作依据，防止凭空编造
            if (op.ids("evidence").toSet().intersect(poolIds).size < 2) return@mapNotNull null
            JsonObject(
                op + mapOf(
                    "category" to JsonPrimitive(Categories.INSIGHT),
                    "importance" to JsonPrimitive(maxOf(3, clampImportance(op.int("importance")))),
                ),
            )
        }
        val errors = mutableListOf<String>()
        val stats = OperationsApplier(repo, render.zone).apply(
            ops, now, source = Sources.REFLECTION, maxImportance = 4,
            allowedIds = insights.map { it.id }.toSet(), errors = errors,
        )
        errors.take(5).forEach { diag.log(TAG, "reflection skipped an operation: $it") }
        data.str("digest")?.trim()?.takeIf { it.isNotEmpty() }?.let { repo.setDigest(it.take(settings.digestMaxChars)) }
        val current = repo.allMemories().filter { it.category == Categories.INSIGHT && !it.archived }
        weakest(current, now, half).take(maxOf(0, current.size - settings.maxInsights)).forEach { repo.archiveMemory(it.id) }
        vectors.index()
        return Result.Done(stats)
    }

    companion object {
        private const val TAG = "memory"
    }
}
