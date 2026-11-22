package org.xmyun.starmate.feature.memory

import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.xmyun.starmate.core.model.ATTACHMENT_PREVIEW_KEY
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.AttachmentTypes
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.CompletedTurn
import org.xmyun.starmate.core.model.DaySummary
import org.xmyun.starmate.core.model.DaySummarySource
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.MemoryCategory
import org.xmyun.starmate.core.model.MemoryWriter
import org.xmyun.starmate.core.model.TurnListener
import org.xmyun.starmate.feature.memory.consolidate.Consolidator
import org.xmyun.starmate.feature.memory.consolidate.NewMemory
import org.xmyun.starmate.feature.memory.data.MemoryRepository
import org.xmyun.starmate.feature.memory.model.Categories
import org.xmyun.starmate.feature.memory.model.MemorySettings
import org.xmyun.starmate.feature.memory.model.Sources
import org.xmyun.starmate.feature.memory.text.cleanContent
import org.xmyun.starmate.feature.memory.work.ConsolidateScheduler
import org.xmyun.starmate.feature.memory.work.ConsolidateTrigger

/** 每轮对话原样存成一轮原话（包括主动消息），攒满 6 轮请求整理。 */
@Singleton
class MemoryTurnListener @Inject constructor(
    private val repo: MemoryRepository,
    private val scheduler: ConsolidateScheduler,
    private val settings: MemorySettings,
    private val diag: DiagLog,
) : TurnListener {
    internal var trigger: ConsolidateTrigger = scheduler

    override suspend fun onTurnCompleted(turn: CompletedTurn) {
        // 用户发起的一轮成功了：这一轮注入过的关心提醒现在才算“提过了”
        if (turn.userMessage != null) repo.commitStagedNudge()
        val user = turn.userMessage?.let(::textOf).orEmpty()
        val reply = textOf(turn.assistantMessage)
        if (user.isBlank() && reply.isBlank()) return
        repo.addEpisode(turn.assistantMessage.createdAt.toEpochMilli(), user, reply, turn.purpose.name)
        val pending = repo.countEpisodesAfter(repo.state().consolidatedUpto)
        if (pending >= settings.consolidateEvery) {
            try {
                if (trigger === scheduler) scheduler.start()
                trigger.requestNow()
            } catch (e: IllegalStateException) {
                diag.log(TAG, "could not schedule consolidation: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "memory"

        /** 存显示文字；语音条等附件的文字稿从 preview 取。 */
        fun textOf(message: ChatMessage): String {
            val previews = message.attachments.mapNotNull(::previewOf)
            val parts = if (message.role == org.xmyun.starmate.core.model.Role.USER) {
                previews + message.displayText
            } else {
                listOf(message.displayText) + message.attachments.filter { it.type == AttachmentTypes.VOICE }.mapNotNull(::previewOf)
            }
            val cleaned = parts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            // 显示文字里已经包含的文字稿不再重复
            return cleaned.filterIndexed { index, text -> cleaned.take(index).none { it.contains(text) } }
                .joinToString("\n")
        }

        private fun previewOf(attachment: Attachment): String? =
            (attachment.payload[ATTACHMENT_PREVIEW_KEY] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

/** ABOUT_USER 写成置顶的核心记忆；BETWEEN_US 写进“我们之间”；其他类别按普通活跃记忆。 */
@Singleton
class DefaultMemoryWriter @Inject constructor(
    private val repo: MemoryRepository,
    private val clock: Clock,
) : MemoryWriter {
    override suspend fun add(text: String, category: String, importance: Int) {
        val content = cleanContent(text)
        if (content.isEmpty()) return
        val now = clock.millis()
        val (cat, pinned) = when (category) {
            MemoryCategory.ABOUT_USER -> Categories.PROFILE to true
            MemoryCategory.BETWEEN_US -> Categories.SELF to false
            else -> Categories.OTHER to false
        }
        val existing = repo.allMemories().firstOrNull { it.isValid && it.content == content }
        if (existing != null) {
            // 重复写入视为又被提起：强化它；关于用户的事补上置顶
            repo.touch(listOf(existing.id), now)
            if (pinned && !existing.pinned) {
                repo.updateMemory(existing.id, org.xmyun.starmate.feature.memory.consolidate.MemPatch(pinned = true, archived = false), now)
            }
            return
        }
        repo.addMemory(NewMemory(content = content, category = cat, importance = importance.coerceIn(1, 5), pinned = pinned, source = Sources.MANUAL), now)
    }

    /** 用和存原话时相同的规则（[MemoryTurnListener.textOf]）算出回复文字，再去原话里找。 */
    override suspend fun forgetReply(message: ChatMessage) {
        repo.redactReply(MemoryTurnListener.textOf(message))
    }
}

/**
 * 日记和主动消息用：这段时间的对话轮数、梗概和新增记忆。
 * refresh = true 时先把这段时间里未整理的原话整理完；false 时只读已有结果，不调用模型。
 */
@Singleton
class DefaultDaySummarySource @Inject constructor(
    private val repo: MemoryRepository,
    private val consolidator: Consolidator,
    private val diag: DiagLog,
) : DaySummarySource {
    override suspend fun daySummary(from: Instant, to: Instant, refresh: Boolean): DaySummary? {
        val start = from.toEpochMilli()
        val end = to.toEpochMilli()
        val episodes = repo.episodesBetween(start, end)
        if (episodes.isEmpty()) return null
        if (refresh && repo.countEpisodesAfterUntil(repo.state().consolidatedUpto, end) > 0) {
            val outcome = consolidator.consolidatePending(untilTs = end)
            if (outcome !is Consolidator.Outcome.Done && outcome !is Consolidator.Outcome.NothingPending) {
                diag.log(TAG, "day summary refresh incomplete: ${outcome.javaClass.simpleName}")
            }
        }
        val gist = repo.gistsOverlapping(start, end - 1).joinToString("\n") { it.summary }
        val newMemories = repo.memoriesCreatedBetween(start, end)
            .filter { it.isValid && !it.archived }
            .map { it.content }
            .take(MAX_NEW_MEMORIES)
        return DaySummary(
            turnCount = episodes.count { it.userText.isNotBlank() },
            gist = gist,
            newMemories = newMemories,
        )
    }

    companion object {
        private const val TAG = "memory"
        const val MAX_NEW_MEMORIES = 30
    }
}
