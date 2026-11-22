package org.xmyun.starmate.feature.voice

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import javax.inject.Inject
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.core.ui.contract.SettingsGroup
import org.xmyun.starmate.core.ui.contract.SettingsItem
import org.xmyun.starmate.feature.voice.ui.VoiceSettingsScreen

class VoiceFeatureEntry @Inject constructor() : FeatureEntry {

    override val settingsItems: List<SettingsItem> = listOf(
        SettingsItem(
            route = ROUTE,
            group = SettingsGroup.ABILITIES,
            order = 30,
            title = R.string.voice_settings_title,
            icon = Icons.Outlined.GraphicEq,
        ),
    )

    override fun NavGraphBuilder.routes(nav: NavController) {
        composable(ROUTE) {
            VoiceSettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenModels = { nav.navigate(MODELS_ROUTE) },
            )
        }
    }

    companion object {
        const val ROUTE = "settings/voice"

        /** 模型配置页，由 feature/chat 注册。 */
        const val MODELS_ROUTE = "settings/models"
    }
}
