package org.xmyun.starmate

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.core.ui.contract.BottomTab
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.core.ui.contract.SettingsGroup
import org.xmyun.starmate.core.ui.contract.SettingsItem

class OpenableRoutesTest {

    private val chat = object : FeatureEntry {
        override val tabs = listOf(
            BottomTab("chat", 0, 0, Icons.Outlined.Settings),
            BottomTab("settings", 3, 0, Icons.Outlined.Settings),
        )
        override val settingsItems = listOf(
            SettingsItem("settings/models", SettingsGroup.MODELS, 0, 0, Icons.Outlined.Settings),
        )
    }

    private val proactive = object : FeatureEntry {
        override val settingsItems = listOf(
            SettingsItem("settings/proactive", SettingsGroup.ABILITIES, 20, 0, Icons.Outlined.Settings),
        )
    }

    @Test
    fun tabsAndSettingsPagesAreOpenable() {
        val routes = openableRoutes(setOf(chat, proactive))
        assertEquals(setOf("chat", "settings", "settings/models", "settings/proactive"), routes)
    }

    @Test
    fun unknownOnboardingAndDetailRoutesAreNotOpenable() {
        val routes = openableRoutes(setOf(chat, proactive))
        // 别的应用随手传进来的字符串、引导页、带参数的详情页都不在名单里
        assertFalse("x" in routes)
        assertFalse("onboarding/companion" in routes)
        assertFalse("diary/12" in routes)
        assertFalse("" in routes)
    }

    @Test
    fun noEntriesMeansNothingIsOpenable() {
        assertTrue(openableRoutes(emptySet()).isEmpty())
    }
}
