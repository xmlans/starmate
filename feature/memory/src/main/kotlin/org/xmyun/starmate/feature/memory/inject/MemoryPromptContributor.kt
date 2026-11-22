package org.xmyun.starmate.feature.memory.inject

import java.time.Clock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.MessageStatus
import org.xmyun.starmate.core.model.MessageStore
import org.xmyun.starmate.core.model.PromptContributor
import org.xmyun.starmate.core.model.PromptOrder
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.memory.consolidate.RenderSource
import org.xmyun.starmate.feature.memory.data.MemoryRepository
import org.xmyun.starmate.feature.memory.model.MemorySettings
import org.xmyun.starmate.feature.memory.retrieval.Recaller
import org.xmyun.starmate.feature.memory.text.estimateTokens

/**
 * 稳定快照：放进 system prompt，只看强度不看当前消息。记忆不变时内容逐字不变，提示词缓存能命中。
 * 按 (数据库代数, 记忆版本, 概况, 预算, 语言) 缓存。
 */
@Singleton
class SnapshotBuilder @Inject constructor(
    private val repo: MemoryRepository,
    private val renders: RenderSource,
    private val settings: MemorySettings,
    private val clock: Clock,
) {
    data class Snapshot(val block: String, val ids: List<Long>)

    private data class Key(val generation: Long, val revision: Long, val digest: String, val budget: Int, val locale: Locale)

    private val lock = Mutex()
    private var cached: Pair<Key, Snapshot>? = null

    suspend fun snapshot(budget: Int): Snapshot = lock.withLock {
        val render = renders.render()
        val state = repo.state()
        val key = Key(repo.holder.generation, state.revision, state.digest, budget, render.texts.locale)
        cached?.let { (k, s) -> if (k == key) return@withLock s }
        val digest = state.digest.take(maxOf(0, budget / 2))
        val memories = repo.allMemories()
        val now = clock.millis()
        val chosen = render.selectSnapshot(memories, now, budget, settings.halfLifeDays)
        val (block, fitted) = render.fitSnapshot(chosen, digest, budget, now, settings.halfLifeDays)
        Snapshot(block, fitted.map { it.id }).also { cached = key to it }
    }

    /** 最近一次生成的快照里的记忆编号（动态回想不再重复它们）。 */
    fun lastIds(): Set<Long> = cached?.second?.ids?.toSet() ?: emptySet()
}

/** 只对聊天、主动消息、日记生效；访谈和记忆整理本身不注入。 */
@Singleton
class MemoryPromptContributor @Inject constructor(
    private val repo: MemoryRepository,
    private val snapshots: SnapshotBuilder,
    private val recaller: Recaller,
    private val renders: RenderSource,
    private val settings: MemorySettings,
    private val clock: Clock,
    private val messages: MessageStore,
    private val prefs: CompanionPrefs,
) : PromptContributor {

    override val order: Int = PromptOrder.MEMORY

    private fun snapshotBudget(hint: String): Int =
        maxOf(0, settings.maxInjectTokens - settings.recallInjectTokens - (if (hint.isEmpty()) 0 else estimateTokens(hint) + 10))

    override suspend fun stable(purpose: TurnPurpose): String? {
        if (purpose !in ACTIVE) return null
        val texts = renders.render().texts
        // 记忆工具只在聊天里提供，说明也只在聊天里加
        val hint = if (purpose == TurnPurpose.CHAT) texts["fragment_hint"] else ""
        val snapshot = snapshots.snapshot(snapshotBudget(texts["fragment_hint"]))
        return listOf(snapshot.block, hint).filter { it.isNotBlank() }.joinToString("\n\n").ifEmpty { null }
    }

    override suspend fun dynamic(purpose: TurnPurpose, userText: String?): String? {
        if (purpose !in ACTIVE || userText.isNullOrBlank()) return null
        val render = renders.render()
        val now = clock.millis()
        val memories = repo.allMemories()
        val budget = settings.recallInjectTokens
        val parts = mutableListOf<String>()
        fun append(text: String): Boolean {
            if (text.isNotEmpty() && estimateTokens((parts + text).joinToString("\n")) <= budget) {
                parts += text
                return true
            }
            return false
        }

        // 顺带关心：快到了或刚过去的事，同一件事 3 天内只提一次
        val care = dueReminders(memories, now).toMutableList()
        var careText = render.renderCare(care, now)
        while (care.isNotEmpty() && estimateTokens(careText) > budget / 2) {
            care.removeAt(care.lastIndex)
            careText = render.renderCare(care, now)
        }
        // 这里只暂存；MemoryTurnListener 在这一轮成功后才提交
        repo.stageNudge(if (append(careText)) care.map { it.id } else emptyList(), now)

        val history = historyMessages()
        val historyTexts = history.flatMap { listOf(it.rawText, it.displayText) }
        val context = history.takeLast(RECENT_CONTEXT_ITEMS).joinToString("\n") { it.displayText }
        val qvec = recaller.embedQuery(userText)
        val snapshotIds = snapshots.lastIds()
        val mem = recaller.memoryRelevance(memories, userText, context, qvec)
        val hasEpisodes = repo.countEpisodes() > 0
        val pick = recaller.recall(
            render, memories, userText, context, qvec, now,
            exclude = snapshotIds + care.map { it.id },
            budget = maxOf(0, budget - estimateTokens(parts.joinToString("\n")) - 2),
            maxEpisodes = if (hasEpisodes) settings.recallEpisodes else 0,
            alwaysEpisodes = false,
            excludeEpisodes = if (hasEpisodes) episodesInHistory(historyTexts, history.size) else emptySet(),
            mem = mem,
        )
        val recalled = append(render.renderRecall(pick, now))
        // 被想到的记忆得到强化：回想出来的，以及快照里和这条消息很相关的
        val inSnapshot = memories.filter { it.id in snapshotIds }
        val snapshotRel = if (inSnapshot.isEmpty()) emptyMap() else recaller.memoryRelevance(inSnapshot, userText, "", qvec).rel
        val thought = (if (recalled) pick.memories.map { it.id } else emptyList()) + snapshotRel.filterValues { it >= 0.5 }.keys
        if (thought.isNotEmpty()) repo.touch(thought, now)
        return parts.joinToString("\n").ifEmpty { null }
    }

    /** 引擎这一轮会带上的历史：和引擎同样按 token 预算从新到旧取。 */
    private suspend fun historyMessages(): List<ChatMessage> {
        val budget = prefs.historyTokenBudget.first()
        val eligible = messages.recent(HISTORY_FETCH).filter {
            it.status == MessageStatus.COMPLETE && it.rawText.isNotBlank() && (it.role == Role.USER || it.role == Role.ASSISTANT)
        }
        val picked = ArrayList<ChatMessage>()
        var used = 0
        for (m in eligible.asReversed()) {
            val cost = estimateTokens(m.rawText)
            if (picked.size >= 2 && used + cost > budget) break
            picked += m
            used += cost
        }
        return picked.asReversed()
    }

    /** 本轮上下文里已经有的原话：自动回想不再把它们当成往事重复注入。 */
    private suspend fun episodesInHistory(texts: List<String>, size: Int): Set<Long> {
        val cleaned = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return emptySet()
        val exact = cleaned.toSet()
        val joined = cleaned.joinToString("\n")
        fun present(text: String) = text in exact || joined.contains(text)
        return repo.recentEpisodes(minOf(HISTORY_CHECK_LIMIT, maxOf(20, size))).filter { e ->
            val user = e.userText.trim()
            val reply = e.replyText.trim()
            reply.isNotEmpty() && present(reply) && (user.isEmpty() || present(user))
        }.map { it.id }.toSet()
    }

    companion object {
        val ACTIVE = setOf(TurnPurpose.CHAT, TurnPurpose.PROACTIVE, TurnPurpose.DIARY)
        private const val RECENT_CONTEXT_ITEMS = 4
        private const val HISTORY_FETCH = 200
        private const val HISTORY_CHECK_LIMIT = 500
    }
}
