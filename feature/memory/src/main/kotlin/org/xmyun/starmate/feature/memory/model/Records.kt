package org.xmyun.starmate.feature.memory.model

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

const val DAY_MS = 86_400_000L

/**
 * fragment：聊天中她随手记下的碎片，整理时升级成正式分类或被合并掉。
 * insight：反思时从多条记忆里归纳出的印象。
 * self：她自己的记忆——答应过的事、和用户的共同经历、玩笑和昵称（“我们之间”）。
 */
object Categories {
    const val PROFILE = "profile"
    const val PREFERENCE = "preference"
    const val RELATION = "relation"
    const val SELF = "self"
    const val FACT = "fact"
    const val EVENT = "event"
    const val INSIGHT = "insight"
    const val OTHER = "other"
    const val FRAGMENT = "fragment"

    val ALL = listOf(PROFILE, PREFERENCE, RELATION, SELF, FACT, EVENT, INSIGHT, OTHER, FRAGMENT)
}

/** user：用户说的；ai：她随手记；manual：用户或其他模块直接写入；reflection：反思归纳。 */
object Sources {
    const val USER = "user"
    const val AI = "ai"
    const val MANUAL = "manual"
    const val REFLECTION = "reflection"

    val ALL = listOf(USER, AI, MANUAL, REFLECTION)
}

/** 一条记忆。时间都是毫秒时间戳。 */
data class Mem(
    val id: Long,
    val content: String,
    val category: String = Categories.OTHER,
    val importance: Int = 3,
    val pinned: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val lastUsedAt: Long = 0,
    val hits: Int = 0,
    val tags: String = "",
    val archived: Boolean = false,
    val source: String = Sources.USER,
    val eventAt: Long? = null,
    val invalidAt: Long? = null,
    val episodeId: Long? = null,
    /** 情绪分量 0–3：刻骨铭心的事遗忘得慢得多。 */
    val emotion: Int = 0,
    /** 上次顺带关心这件事的时间。 */
    val nudgedAt: Long? = null,
) {
    val isValid: Boolean get() = invalidAt == null
    val isCore: Boolean get() = isValid && (pinned || importance >= 5)
    val text: String get() = "$content $tags"

    /** 遗忘曲线：越久没被更新或用到越弱；被用得越多、情绪越强，半衰期越长。 */
    fun decay(now: Long, halfLifeDays: Double): Double {
        val last = max(updatedAt, lastUsedAt)
        val ageDays = max(0L, now - last).toDouble() / DAY_MS
        val halfLife = max(0.1, halfLifeDays) * (1 + 0.5 * ln(1.0 + hits)) * (1 + 0.5 * emotion)
        return 0.5.pow(ageDays / halfLife)
    }

    fun strength(now: Long, halfLifeDays: Double): Double {
        if (pinned) return Double.POSITIVE_INFINITY
        var weight = 0.4 + 0.6 * importance / 5.0
        if (!isValid) weight *= 0.2
        return weight * (0.3 + 0.7 * decay(now, halfLifeDays))
    }
}

/** 一轮原话：用户说的 + 她回的，永久保存。主动消息那轮 userText 为空。 */
data class Episode(
    val id: Long,
    val ts: Long,
    val userText: String,
    val replyText: String,
    val keys: String = "",
    val purpose: String = "",
) {
    val text: String get() = "$userText $replyText"
}

/** 一段对话的梗概。 */
data class Gist(
    val id: Long,
    val startTs: Long,
    val endTs: Long,
    val firstEpisode: Long,
    val lastEpisode: Long,
    val summary: String,
    val keys: String = "",
)

/** 时间范围，两端都包含。 */
data class Span(val start: Long, val end: Long) {
    operator fun contains(ts: Long?): Boolean = ts != null && ts in start..end
    fun overlaps(from: Long, to: Long): Boolean = to >= start && from <= end
}

/** 默认参数与参考实现一致。 */
data class MemorySettings(
    val consolidateEvery: Int = 6,
    val idleMinutes: Long = 10,
    val maxBatchRounds: Int = 30,
    /** 一次整理里新对话部分的 token 上限，防止一次把太多原话塞进提示词。 */
    val maxBatchTokens: Int = 6000,
    /** 每轮原话每一侧给整理模型看的最大字数，超出取首尾。 */
    val batchExcerptChars: Int = 1500,
    val maxActive: Int = 300,
    val maxArchive: Int = 2000,
    val maxInjectTokens: Int = 12000,
    val recallInjectTokens: Int = 1500,
    val recallEpisodes: Int = 3,
    val episodeSearchWindow: Int = 3000,
    val consolidateContextTokens: Int = 3000,
    val halfLifeDays: Double = 30.0,
    val digestMaxChars: Int = 500,
    val reflectEvery: Int = 5,
    val maxInsights: Int = 20,
    val maxFragmentsPerTurn: Int = 3,
    val maxFragmentImportance: Int = 4,
    val poisonRetries: Int = 3,
    val readEpisodeMaxChars: Int = 1200,
)
