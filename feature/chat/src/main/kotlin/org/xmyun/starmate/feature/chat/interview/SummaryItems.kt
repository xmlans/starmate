package org.xmyun.starmate.feature.chat.interview

/** 小结卡片上的一条。列表字段每一项单独一条，index 是它在列表里的位置。 */
data class SummaryItem(val field: SummaryField, val index: Int, val value: String)

enum class SummaryField { NICKNAME, BIRTHDAY, SLEEP_TIME, WAKE_TIME, OCCUPATION, LIKES, DISLIKES, PERSONA, PROACTIVITY, AVOID_TOPICS, OTHER_FACTS }

/** 资料和卡片条目之间的换算，纯函数，方便测试。 */
object SummaryItems {

    fun of(profile: OnboardingProfile): List<SummaryItem> {
        val items = mutableListOf<SummaryItem>()
        fun single(field: SummaryField, value: String?) {
            if (!value.isNullOrBlank()) items += SummaryItem(field, 0, value)
        }
        fun many(field: SummaryField, values: List<String>?) {
            values.orEmpty().forEachIndexed { i, v -> if (v.isNotBlank()) items += SummaryItem(field, i, v) }
        }
        single(SummaryField.NICKNAME, profile.nickname)
        single(SummaryField.BIRTHDAY, profile.birthday)
        single(SummaryField.SLEEP_TIME, profile.sleepTime)
        single(SummaryField.WAKE_TIME, profile.wakeTime)
        single(SummaryField.OCCUPATION, profile.occupation)
        many(SummaryField.LIKES, profile.likes)
        many(SummaryField.DISLIKES, profile.dislikes)
        single(SummaryField.PERSONA, profile.personaPreference)
        single(SummaryField.PROACTIVITY, profile.proactivity)
        many(SummaryField.AVOID_TOPICS, profile.avoidTopics)
        many(SummaryField.OTHER_FACTS, profile.otherFacts)
        return items
    }

    /** 改一条；新值为空白时等同删除。 */
    fun edit(profile: OnboardingProfile, item: SummaryItem, newValue: String): OnboardingProfile {
        val value = newValue.trim().takeIf { it.isNotEmpty() }
        return when (item.field) {
            SummaryField.NICKNAME -> profile.copy(nickname = value)
            SummaryField.BIRTHDAY -> profile.copy(birthday = value)
            SummaryField.SLEEP_TIME -> profile.copy(sleepTime = value?.let(OnboardingProfile::normalizeTime))
            SummaryField.WAKE_TIME -> profile.copy(wakeTime = value?.let(OnboardingProfile::normalizeTime))
            SummaryField.OCCUPATION -> profile.copy(occupation = value)
            SummaryField.PERSONA -> profile.copy(personaPreference = value)
            SummaryField.PROACTIVITY -> profile.copy(proactivity = OnboardingProfile.normalizeProactivity(value))
            SummaryField.LIKES -> profile.copy(likes = profile.likes.replace(item.index, value))
            SummaryField.DISLIKES -> profile.copy(dislikes = profile.dislikes.replace(item.index, value))
            SummaryField.AVOID_TOPICS -> profile.copy(avoidTopics = profile.avoidTopics.replace(item.index, value))
            SummaryField.OTHER_FACTS -> profile.copy(otherFacts = profile.otherFacts.replace(item.index, value))
        }.normalized()
    }

    fun remove(profile: OnboardingProfile, item: SummaryItem): OnboardingProfile = edit(profile, item, "")

    private fun List<String>?.replace(index: Int, value: String?): List<String>? {
        val list = this?.toMutableList() ?: return null
        if (index !in list.indices) return this
        if (value == null) list.removeAt(index) else list[index] = value
        return list.takeIf { it.isNotEmpty() }
    }
}
