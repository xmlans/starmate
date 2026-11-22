package org.xmyun.starmate.feature.proactive

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import javax.inject.Inject
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.core.ui.contract.SettingsGroup
import org.xmyun.starmate.core.ui.contract.SettingsItem
import org.xmyun.starmate.feature.proactive.ui.BatteryGuideScreen
import org.xmyun.starmate.feature.proactive.ui.ProactiveRoutes
import org.xmyun.starmate.feature.proactive.ui.ProactiveSettingsScreen

class ProactiveFeatureEntry @Inject constructor(
    bootstrap: ProactiveBootstrap,
) : FeatureEntry {

    init {
        bootstrap.start()
    }

    override val settingsItems: List<SettingsItem> = listOf(
        SettingsItem(
            route = ProactiveRoutes.SETTINGS,
            group = SettingsGroup.ABILITIES,
            order = 20,
            title = R.string.proactive_settings_title,
            icon = Icons.Outlined.NotificationsActive,
        ),
    )

    override fun NavGraphBuilder.routes(nav: NavController) {
        composable(ProactiveRoutes.SETTINGS) {
            ProactiveSettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenBatteryGuide = { nav.navigate(ProactiveRoutes.BATTERY) },
            )
        }
        composable(ProactiveRoutes.BATTERY) {
            BatteryGuideScreen(onBack = { nav.popBackStack() })
        }
    }
}
