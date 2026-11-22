package org.xmyun.starmate.core.model

import kotlinx.coroutines.flow.Flow

data class CompanionProfile(
    val id: Long = DEFAULT_COMPANION_ID,
    val name: String,
    val avatarUri: String? = null,
    val persona: String,
    /** 初识访谈得到的相处偏好，拼在人设之后。 */
    val relationshipNotes: String = "",
    val userNickname: String = "",
)

interface CompanionStore {
    val profile: Flow<CompanionProfile>
    suspend fun current(): CompanionProfile
    suspend fun update(transform: (CompanionProfile) -> CompanionProfile)
}

enum class OnboardingState { NOT_STARTED, INTERVIEWING, DONE }

enum class Clinginess(val maxPerDay: Int) { OFF(0), LOW(1), MEDIUM(2), HIGH(4) }

/** 一天中的分钟数，0–1439。start 大于 end 表示跨午夜。 */
data class QuietHours(val startMinute: Int, val endMinute: Int) {
    fun contains(minuteOfDay: Int): Boolean =
        if (startMinute <= endMinute) minuteOfDay in startMinute until endMinute
        else minuteOfDay >= startMinute || minuteOfDay < endMinute

    companion object {
        val DEFAULT = QuietHours(startMinute = 23 * 60, endMinute = 8 * 60)
    }
}

interface CompanionPrefs {
    val adultConfirmed: Flow<Boolean>
    suspend fun setAdultConfirmed(value: Boolean)

    val onboardingState: Flow<OnboardingState>
    suspend fun setOnboardingState(state: OnboardingState)

    /** 访谈结束后、用户确认前暂存的资料 JSON。 */
    val pendingOnboardingProfile: Flow<String?>
    suspend fun setPendingOnboardingProfile(json: String?)

    val quietHours: Flow<QuietHours>
    suspend fun setQuietHours(value: QuietHours)

    val clinginess: Flow<Clinginess>
    suspend fun setClinginess(value: Clinginess)

    /** 后台用途的每日 token 预算。默认 MEMORY 80000、PROACTIVE 20000、DIARY 10000。 */
    fun dailyBudget(purpose: TurnPurpose): Flow<Int>
    suspend fun setDailyBudget(purpose: TurnPurpose, tokens: Int)

    /** 发给模型的历史上限（估算 token），默认 8000。 */
    val historyTokenBudget: Flow<Int>
    suspend fun setHistoryTokenBudget(tokens: Int)
}
