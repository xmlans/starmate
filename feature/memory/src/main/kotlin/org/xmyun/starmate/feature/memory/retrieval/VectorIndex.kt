package org.xmyun.starmate.feature.memory.retrieval

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.ModelRouter
import org.xmyun.starmate.feature.memory.data.MemoryRepository
import org.xmyun.starmate.feature.memory.model.Episode

/**
 * 向量索引：配了 Embedding 时给记忆、原话、梗概建向量；没配时什么都不做。
 * 调用失败时当次退回关键词检索并写诊断日志，不影响聊天。
 */
@Singleton
class VectorIndex @Inject constructor(
    private val router: ModelRouter,
    private val repo: MemoryRepository,
    private val diag: DiagLog,
) {
    suspend fun available(): Boolean = try {
        router.embedding() != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** 失败、超时或没配置时返回 null。 */
    suspend fun embed(texts: List<String>, timeoutMs: Long = EMBED_TIMEOUT_MS): List<FloatArray>? {
        if (texts.isEmpty()) return null
        return try {
            val client = router.embedding() ?: return null
            val result = ArrayList<FloatArray>()
            for (chunk in texts.chunked(EMBED_BATCH)) {
                val vectors = withTimeoutOrNull(timeoutMs) { client.embed(chunk) }
                if (vectors == null) {
                    diag.log(TAG, "embedding timed out, keyword search only this time")
                    return null
                }
                result += vectors
            }
            if (result.size == texts.size) result else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.log(TAG, "embedding failed, keyword search only this time: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 查询向量在回复之前计算，等太久会拖慢回复；还没建过任何向量时不必算。 */
    suspend fun embedQuery(text: String?): FloatArray? {
        if (text.isNullOrBlank() || !repo.hasVectors()) return null
        return embed(listOf(text.take(2000)), QUERY_TIMEOUT_MS)?.firstOrNull()
    }

    /** 给还没有向量的记忆、原话、梗概补上向量。返回新建的数量。 */
    suspend fun index(limit: Int = 256): Int {
        if (!available()) return 0
        var done = 0
        val memories = repo.unindexedMemories(limit)
        if (memories.isNotEmpty()) {
            val inputs = memories.associate { it.id to it.text }
            embed(inputs.values.toList())?.let { vecs ->
                repo.putVectors("m", memories.map { it.id }.zip(vecs), inputs) { id -> repo.memory(id)?.text }
                done += vecs.size
            } ?: return done
        }
        val episodes = repo.unindexedEpisodes(limit)
        if (episodes.isNotEmpty()) {
            val inputs = episodes.associate { it.id to episodeInput(it) }
            embed(inputs.values.toList())?.let { vecs ->
                repo.putVectors("e", episodes.map { it.id }.zip(vecs), inputs) { id -> repo.episode(id)?.let(::episodeInput) }
                done += vecs.size
            } ?: return done
        }
        val gists = repo.unindexedGists(limit)
        if (gists.isNotEmpty()) {
            val inputs = gists.associate { it.id to it.summary }
            embed(inputs.values.toList())?.let { vecs ->
                val byId = gists.associateBy { it.id }
                repo.putVectors("g", gists.map { it.id }.zip(vecs), inputs) { id -> byId[id]?.summary }
                done += vecs.size
            }
        }
        return done
    }

    /** 换了 Embedding 模型后重建：清掉全部向量再逐批补齐。 */
    suspend fun rebuild(maxRounds: Int = 50): Int {
        repo.deleteAllVectors()
        var total = 0
        repeat(maxRounds) {
            val done = index()
            if (done == 0) return total
            total += done
        }
        return total
    }

    companion object {
        private const val TAG = "memory"
        const val EMBED_BATCH = 32
        const val EMBED_TIMEOUT_MS = 30_000L
        const val QUERY_TIMEOUT_MS = 3_000L

        fun episodeInput(e: Episode): String = "${e.userText} ${e.replyText}".take(2000)
    }
}
