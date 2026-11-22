package org.xmyun.starmate.feature.memory.consolidate

import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.feature.memory.data.MemoryRepository
import org.xmyun.starmate.feature.memory.inject.relatedForConsolidation
import org.xmyun.starmate.feature.memory.model.Episode
import org.xmyun.starmate.feature.memory.model.MemorySettings
import org.xmyun.starmate.feature.memory.retrieval.Recaller
import org.xmyun.starmate.feature.memory.retrieval.VectorIndex
import org.xmyun.starmate.feature.memory.text.estimateTokens
import org.xmyun.starmate.feature.memory.text.excerpt

/**
 * 整理：把未处理的原话交给模型提炼、更新、失效、合并记忆，并写一句梗概。
 * 同一时间只有一次整理（以及压缩、反思）在跑。
 */
@Singleton
class Consolidator @Inject constructor(
    private val repo: MemoryRepository,
    private val llm: MemoryLlm,
    private val recaller: Recaller,
    private val vectors: VectorIndex,
    private val archiver: Archiver,
    private val reflector: Reflector,
    private val renders: RenderSource,
    private val settings: MemorySettings,
    private val clock: Clock,
    private val diag: DiagLog,
) {
    private val mutex = Mutex()

    sealed interface Outcome {
        data class Done(val stats: ApplyStats) : Outcome
        data object NothingPending : Outcome
        /** 今日后台预算用完：待整理的原话原样保留，下次再试。 */
        data object Budget : Outcome
        /** 失败：原话保留，等下一次触发再试。 */
        data class Failed(val reason: String) : Outcome
        /** 同一段内容连续失败、模型本身正常：跳过这段（原话保留，仍可被回忆）。 */
        data class Skipped(val episodeId: Long) : Outcome
        /** 等待模型期间记忆被清空或导入，结果作废。 */
        data object Stale : Outcome
    }

    /** 整理一批。untilTs 不为空时只整理这个时刻之前的原话。 */
    suspend fun consolidateOnce(untilTs: Long? = null): Outcome = mutex.withLock { consolidateLocked(untilTs) }

    /** 一直整理到没有待处理的原话，或遇到预算用完、失败为止。 */
    suspend fun consolidatePending(untilTs: Long? = null, maxBatches: Int = 20): Outcome {
        var last: Outcome = Outcome.NothingPending
        repeat(maxBatches) {
            val outcome = consolidateOnce(untilTs)
            when (outcome) {
                is Outcome.Done, is Outcome.Skipped -> last = outcome
                Outcome.NothingPending -> return last
                else -> return outcome
            }
        }
        return last
    }

    /** 还有多少轮原话没整理。 */
    suspend fun pendingCount(): Int = repo.countEpisodesAfter(repo.state().consolidatedUpto)

    suspend fun reflectNow(): Reflector.Result = mutex.withLock { reflector.reflect(clock.millis()) }

    /** 每晚的维护：不调模型的遗忘和归档（超过活跃上限时才会调模型压缩）。 */
    suspend fun maintain() = mutex.withLock { archiver.keepInBudget(clock.millis()) }

    private suspend fun consolidateLocked(untilTs: Long?): Outcome {
        val generation = repo.holder.generation
        val state = repo.state()
        val limit = if (state.batchLimit in 1 until settings.maxBatchRounds) state.batchLimit else settings.maxBatchRounds
        val originals = repo.episodesAfter(state.consolidatedUpto, limit)
            .let { list -> if (untilTs == null) list else list.takeWhile { it.ts <= untilTs } }
        if (originals.isEmpty()) return Outcome.NothingPending
        val render = renders.render()
        val batch = capBatch(originals, render.texts["episode_omitted"])
        val now = clock.millis()
        val memories = repo.allMemories()
        val text = batch.joinToString("\n") { it.text }
        val mem = recaller.memoryRelevance(memories, text, "", recaller.embedQuery(text))
        val related = relatedForConsolidation(render, memories, mem.rel, settings.consolidateContextTokens, now, settings.halfLifeDays)
        val prompt = render.buildConsolidatePrompt(related, batch, now, settings.batchExcerptChars)
        val result = llm.call(render.texts["consolidate_system"], prompt)
        if (repo.holder.generation != generation) return Outcome.Stale
        val data = when (result) {
            LlmOutcome.Budget -> {
                diag.log(TAG, "consolidation postponed: daily memory budget used up")
                return Outcome.Budget
            }
            is LlmOutcome.Error -> return failed(batch, limit, "model call failed: ${result.reason}", state.failEpisode, state.failCount)
            is LlmOutcome.Ok -> result.data
        }
        if (!OperationsParser.validOperations(data)) {
            return failed(batch, limit, "model returned an invalid structure", state.failEpisode, state.failCount)
        }
        val errors = mutableListOf<String>()
        val stats = try {
            repo.transaction {
                val applied = OperationsApplier(repo, render.zone).apply(
                    data!!.ops(), now, Provenance(batch),
                    allowedIds = related.map { it.id }.toSet(), errors = errors,
                )
                data.str("gist")?.let { repo.addGist(batch, it) }
                repo.advanceCursor(batch.last().id, 1)
                // 缩小过的批次逐步恢复
                repo.setBatchLimit(if (limit < settings.maxBatchRounds && limit * 2 < settings.maxBatchRounds) limit * 2 else 0)
                applied
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failed(batch, limit, "commit failed and rolled back: ${e.javaClass.simpleName}", state.failEpisode, state.failCount)
        }
        errors.take(5).forEach { diag.log(TAG, "skipped an operation: $it") }
        afterSuccess()
        return Outcome.Done(stats)
    }

    private suspend fun afterSuccess() {
        val generation = repo.holder.generation
        vectors.index()
        if (repo.holder.generation != generation) return
        val now = clock.millis()
        archiver.keepInBudget(now)
        val consolidations = repo.state().consolidations
        if (settings.reflectEvery > 0 && consolidations % settings.reflectEvery == 0) reflector.reflect(now)
    }

    /** 新对话部分的 token 上限：一次整理的输入有上限，不把太多原话塞进提示词；至少一轮。 */
    private fun capBatch(originals: List<Episode>, omitted: String): List<Episode> {
        val batch = ArrayList<Episode>()
        var used = 0
        for (e in originals) {
            val cost = estimateTokens(excerpt(e.userText, settings.batchExcerptChars, omitted)) +
                estimateTokens(excerpt(e.replyText, settings.batchExcerptChars, omitted)) + 12
            if (batch.isNotEmpty() && used + cost > settings.maxBatchTokens) break
            batch += e
            used += cost
        }
        return batch
    }

    /**
     * 先缩小批次找出出错的那一轮；同一轮单独整理仍连续失败、而模型对空白对话能正常回答时，
     * 跳过这一轮（原话照常保存、仍可回忆），避免一条内容让整理永远卡住。
     */
    private suspend fun failed(batch: List<Episode>, limit: Int, reason: String, failEpisode: Long, failCount: Int): Outcome {
        diag.log(TAG, "consolidation failed, will retry later: $reason")
        if (batch.size > 1) {
            repo.setFailure(maxOf(1, minOf(limit, batch.size) / 2), 0, 0)
            return Outcome.Failed(reason)
        }
        val episode = batch[0]
        val count = if (failEpisode == episode.id) failCount + 1 else 1
        if (count >= settings.poisonRetries && modelWorks()) {
            repo.advanceCursor(episode.id, 0)
            repo.setBatchLimit(0)
            diag.log(TAG, "episode #${episode.id} failed $count times in a row and was skipped (original text kept): $reason")
            return Outcome.Skipped(episode.id)
        }
        repo.setFailure(1, episode.id, count)
        return Outcome.Failed(reason)
    }

    /** 用一段空白对话试一下整理模型，区分“这段内容出错”和“模型或网络本身出错”。 */
    private suspend fun modelWorks(): Boolean {
        val render = renders.render()
        val result = llm.call(render.texts["consolidate_system"], render.buildConsolidatePrompt(emptyList(), emptyList(), clock.millis(), settings.batchExcerptChars))
        return result is LlmOutcome.Ok && OperationsParser.validOperations(result.data)
    }

    companion object {
        private const val TAG = "memory"
    }
}
