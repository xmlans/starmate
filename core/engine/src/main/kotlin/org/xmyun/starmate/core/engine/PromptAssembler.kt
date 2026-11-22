package org.xmyun.starmate.core.engine

import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import org.xmyun.starmate.core.engine.guidelines.GuidelinesSource
import org.xmyun.starmate.core.model.CompanionProfile
import org.xmyun.starmate.core.model.CompanionStore
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.PromptContributor
import org.xmyun.starmate.core.model.TurnPurpose

/**
 * 拼提示词。
 * - 稳定部分（system prompt）：内置准则 → 人设 → 各 PromptContributor.stable（按 order）。
 *   这里不得出现任何每轮会变的东西，否则提示词缓存失效。
 * - 动态部分：环境块 → 各 PromptContributor.dynamic，附在本轮发给模型的用户消息末尾。
 */
class PromptAssembler @Inject constructor(
    private val guidelines: GuidelinesSource,
    private val companions: CompanionStore,
    contributors: Set<@JvmSuppressWildcards PromptContributor>,
    private val ambient: AmbientBlock,
    private val diag: DiagLog,
) {
    // 同 order 时按类名排，避免集合遍历顺序影响稳定部分
    private val ordered = contributors.sortedWith(
        compareBy<PromptContributor>({ it.order }, { it.javaClass.name }),
    )

    suspend fun stable(purpose: TurnPurpose): String {
        val loaded = guidelines.load()
        val parts = mutableListOf<String>()
        parts += loaded.text.trim()
        parts += personaBlock(companions.current(), loaded.chinese)
        for (contributor in ordered) {
            val piece = guard(contributor, "stable") { contributor.stable(purpose) }
            piece?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
        }
        return parts.filter { it.isNotEmpty() }.joinToString(SECTION_SEPARATOR)
    }

    suspend fun dynamic(purpose: TurnPurpose, userText: String?): String {
        val chinese = guidelines.load().chinese
        val parts = mutableListOf<String>()
        parts += ambient.render(chinese)
        for (contributor in ordered) {
            val piece = guard(contributor, "dynamic") { contributor.dynamic(purpose, userText) }
            piece?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
        }
        return parts.filter { it.isNotEmpty() }.joinToString(SECTION_SEPARATOR)
    }

    /** 发给模型的本轮用户消息。rawText 为 null（Kickoff）时只发动态部分。 */
    fun userContent(rawText: String?, dynamic: String): String = when {
        rawText == null -> dynamic
        dynamic.isBlank() -> rawText
        else -> "$rawText\n\n$DYNAMIC_SEPARATOR\n$dynamic"
    }

    internal fun personaBlock(profile: CompanionProfile, chinese: Boolean): String {
        val lines = mutableListOf<String>()
        val name = profile.name.trim()
        val persona = profile.persona.trim()
        val notes = profile.relationshipNotes.trim()
        val nickname = profile.userNickname.trim()
        if (chinese) {
            lines += "# 你的角色"
            if (name.isNotEmpty()) lines += "你叫$name。"
            if (persona.isNotEmpty()) lines += persona
            if (notes.isNotEmpty()) lines += "\n相处偏好：\n$notes"
            if (nickname.isNotEmpty()) lines += "\n你称呼对方“$nickname”。"
        } else {
            lines += "# Your character"
            if (name.isNotEmpty()) lines += "Your name is $name."
            if (persona.isNotEmpty()) lines += persona
            if (notes.isNotEmpty()) lines += "\nHow the two of you get along:\n$notes"
            if (nickname.isNotEmpty()) lines += "\nYou call them \"$nickname\"."
        }
        return if (lines.size == 1) "" else lines.joinToString("\n")
    }

    private suspend fun guard(
        contributor: PromptContributor,
        part: String,
        block: suspend () -> String?,
    ): String? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        diag.log(TAG, "PromptContributor ${contributor.javaClass.simpleName}.$part failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    companion object {
        const val SECTION_SEPARATOR = "\n\n"
        const val DYNAMIC_SEPARATOR = "---"
        private const val TAG = "engine"
    }
}
