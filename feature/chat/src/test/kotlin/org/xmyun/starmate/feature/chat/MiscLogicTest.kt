package org.xmyun.starmate.feature.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.ui.contract.SettingsGroup
import org.xmyun.starmate.core.ui.contract.SettingsItem
import org.xmyun.starmate.feature.chat.common.ChatRoutes
import org.xmyun.starmate.feature.chat.common.ErrorTexts
import org.xmyun.starmate.feature.chat.interview.OnboardingProfile
import org.xmyun.starmate.feature.chat.interview.SummaryField
import org.xmyun.starmate.feature.chat.interview.SummaryItems
import org.xmyun.starmate.feature.chat.onboarding.OnboardingSteps
import org.xmyun.starmate.feature.chat.settings.SettingsViewModel

class MiscLogicTest {

    @Test
    fun onboardingSteps() {
        assertEquals(ChatRoutes.ONBOARDING_WELCOME, OnboardingSteps.firstStep(false, false, OnboardingState.NOT_STARTED))
        assertEquals(ChatRoutes.ONBOARDING_MODEL, OnboardingSteps.firstStep(true, false, OnboardingState.DONE))
        assertEquals(ChatRoutes.ONBOARDING_COMPANION, OnboardingSteps.firstStep(true, true, OnboardingState.NOT_STARTED))
        assertNull(OnboardingSteps.firstStep(true, true, OnboardingState.INTERVIEWING))
        // 从备份恢复：访谈已完成，只剩配置模型
        assertEquals(ChatRoutes.ONBOARDING_MODEL, OnboardingSteps.afterAdult(false, OnboardingState.DONE))
        assertNull(OnboardingSteps.afterModel(OnboardingState.DONE))
        assertEquals(ChatRoutes.ONBOARDING_COMPANION, OnboardingSteps.afterModel(OnboardingState.NOT_STARTED))
    }

    @Test
    fun everyErrorKindHasTextAndKeyErrorsPointToModels() {
        LlmErrorKind.entries.forEach { ErrorTexts.message(it) }
        assertEquals(LlmErrorKind.entries.size, LlmErrorKind.entries.map { ErrorTexts.message(it) }.toSet().size)
        assertEquals(ChatRoutes.SETTINGS_MODELS, ErrorTexts.fixRoute(LlmErrorKind.INVALID_KEY))
        assertEquals(ChatRoutes.SETTINGS_MODELS, ErrorTexts.fixRoute(LlmErrorKind.NOT_CONFIGURED))
        assertNull(ErrorTexts.fixRoute(LlmErrorKind.TIMEOUT))
    }

    @Test
    fun settingsGroupedAndSorted() {
        val icon = Icons.Outlined.Settings
        val items = listOf(
            SettingsItem("b", SettingsGroup.ABILITIES, 90, 1, icon),
            SettingsItem("a", SettingsGroup.ABILITIES, 10, 1, icon),
            SettingsItem("c", SettingsGroup.COMPANION, 20, 1, icon),
            SettingsItem("lab", SettingsGroup.LAB, 10, 1, icon),
        )
        val groups = SettingsViewModel.groupSettings(items)
        assertEquals(listOf(SettingsGroup.COMPANION, SettingsGroup.ABILITIES, SettingsGroup.LAB), groups.map { it.first })
        assertEquals(listOf("a", "b"), groups[1].second.map { it.route })
        assertTrue(SettingsViewModel.groupSettings(emptyList()).isEmpty())
    }

    @Test
    fun summaryItemsEditAndRemove() {
        val profile = OnboardingProfile(nickname = "阿青", likes = listOf("猫", "火锅"), sleepTime = "01:00", proactivity = "often")
        val items = SummaryItems.of(profile)
        assertEquals(5, items.size)
        val hotpot = items.first { it.value == "火锅" }
        assertEquals(listOf("猫"), SummaryItems.remove(profile, hotpot).likes)
        val sleep = items.first { it.field == SummaryField.SLEEP_TIME }
        assertEquals("23:30", SummaryItems.edit(profile, sleep, "23:30").sleepTime)
        val cat = items.first { it.value == "猫" }
        assertNull(SummaryItems.remove(SummaryItems.remove(profile, hotpot), cat).likes)
        val pro = items.first { it.field == SummaryField.PROACTIVITY }
        assertEquals("never", SummaryItems.edit(profile, pro, "never").proactivity)
    }
}
