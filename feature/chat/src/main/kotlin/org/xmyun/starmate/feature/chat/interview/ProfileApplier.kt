package org.xmyun.starmate.feature.chat.interview

import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.CompanionStore
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.MemoryCategory
import org.xmyun.starmate.core.model.MemoryWriter
import org.xmyun.starmate.core.model.QuietHours

/**
 * 把确认后的访谈资料落盘（设计文档 5.1“落盘内容”）：
 * - 每条事实写一条“关于你”的核心记忆（重要度 5），不想提起的话题也写，并写明要回避；
 * - nickname → 用户称呼；sleepTime + wakeTime → 免打扰时段（只有一个时不改）；
 * - proactivity → 粘人程度（没回答不改）；personaPreference → 追加到“相处偏好”。
 * MemoryWriter 是可选的，没有实现时跳过记忆，其余照常。
 */
class ProfileApplier @Inject constructor(
    private val companions: CompanionStore,
    private val prefs: CompanionPrefs,
    private val memoryWriter: Optional<MemoryWriter>,
    private val texts: InterviewTexts,
    private val diag: DiagLog,
) {

    suspend fun apply(raw: OnboardingProfile) {
        val profile = raw.normalized()
        if (profile.isEmpty()) return

        val writer = memoryWriter.orElse(null)
        if (writer != null) {
            for (fact in memoryFacts(profile, texts.chinese)) {
                try {
                    writer.add(fact, MemoryCategory.ABOUT_USER, CORE_IMPORTANCE)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    diag.log(TAG, "memory write failed: ${e.javaClass.simpleName}")
                }
            }
        }

        val nickname = profile.nickname
        val persona = profile.personaPreference
        if (nickname != null || persona != null) {
            companions.update { current ->
                current.copy(
                    userNickname = nickname ?: current.userNickname,
                    relationshipNotes = if (persona == null) {
                        current.relationshipNotes
                    } else {
                        listOf(current.relationshipNotes.trim(), persona).filter { it.isNotEmpty() }.joinToString("\n")
                    },
                )
            }
        }

        quietHours(profile)?.let { prefs.setQuietHours(it) }
        clinginess(profile.proactivity)?.let { prefs.setClinginess(it) }
    }

    companion object {
        const val CORE_IMPORTANCE = 5
        private const val TAG = "chat.onboarding"

        fun quietHours(profile: OnboardingProfile): QuietHours? {
            val start = OnboardingProfile.minuteOfDay(profile.sleepTime) ?: return null
            val end = OnboardingProfile.minuteOfDay(profile.wakeTime) ?: return null
            if (start == end) return null
            return QuietHours(startMinute = start, endMinute = end)
        }

        fun clinginess(proactivity: String?): Clinginess? = when (OnboardingProfile.normalizeProactivity(proactivity)) {
            OnboardingProfile.OFTEN -> Clinginess.HIGH
            OnboardingProfile.SOMETIMES -> Clinginess.LOW
            OnboardingProfile.NEVER -> Clinginess.OFF
            else -> null
        }

        /** 每条事实一句完整的话。 */
        fun memoryFacts(profile: OnboardingProfile, chinese: Boolean): List<String> {
            val facts = mutableListOf<String>()
            profile.nickname?.let {
                facts += if (chinese) "用户希望被称呼为“$it”" else "The user wants to be called \"$it\""
            }
            profile.birthday?.let {
                facts += if (chinese) "用户的生日是 ${birthdayText(it, true)}" else "The user's birthday is ${birthdayText(it, false)}"
            }
            val sleep = profile.sleepTime
            val wake = profile.wakeTime
            when {
                sleep != null && wake != null -> facts +=
                    if (chinese) "用户通常 $sleep 左右睡觉，$wake 左右起床" else "The user usually goes to bed around $sleep and gets up around $wake"
                sleep != null -> facts += if (chinese) "用户通常 $sleep 左右睡觉" else "The user usually goes to bed around $sleep"
                wake != null -> facts += if (chinese) "用户通常 $wake 左右起床" else "The user usually gets up around $wake"
            }
            profile.occupation?.let {
                facts += if (chinese) "用户现在主要在忙：$it" else "The user is currently mainly busy with: $it"
            }
            profile.likes.orEmpty().forEach {
                facts += if (chinese) "用户喜欢$it" else "The user likes $it"
            }
            profile.dislikes.orEmpty().forEach {
                facts += if (chinese) "用户不喜欢$it" else "The user dislikes $it"
            }
            profile.personaPreference?.let {
                facts += if (chinese) "用户希望我们这样相处：$it" else "How the user wants us to get along: $it"
            }
            profile.avoidTopics.orEmpty().forEach {
                facts += if (chinese) "不要主动提起：$it" else "Don't bring this up unprompted: $it"
            }
            profile.otherFacts.orEmpty().forEach { facts += it }
            return facts
        }

        private val MONTH_DAY = Regex("""^(?:(\d{4})-)?(\d{1,2})-(\d{1,2})$""")
        private val MONTHS = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )

        /** "03-05" → “3 月 5 日” / “March 5”；认不出的格式原样返回。 */
        fun birthdayText(value: String, chinese: Boolean): String {
            val match = MONTH_DAY.matchEntire(value.trim()) ?: return value
            val year = match.groupValues[1]
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[3].toInt()
            if (month !in 1..12 || day !in 1..31) return value
            return if (chinese) {
                (if (year.isNotEmpty()) "$year 年 " else "") + "$month 月 $day 日"
            } else {
                "${MONTHS[month - 1]} $day" + if (year.isNotEmpty()) ", $year" else ""
            }
        }
    }
}
