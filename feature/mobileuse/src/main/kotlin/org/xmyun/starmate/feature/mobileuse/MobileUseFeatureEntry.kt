package org.xmyun.starmate.feature.mobileuse

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import javax.inject.Inject
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.core.ui.contract.SettingsGroup
import org.xmyun.starmate.core.ui.contract.SettingsItem
import org.xmyun.starmate.feature.mobileuse.ui.ActionLogScreen
import org.xmyun.starmate.feature.mobileuse.ui.AllowlistScreen
import org.xmyun.starmate.feature.mobileuse.ui.LabScreen
import org.xmyun.starmate.feature.mobileuse.ui.RiskDisclosureScreen

object MobileUseRoutes {
    const val LAB = "settings/lab/mobileuse"
    const val RISK = "settings/lab/mobileuse/risk"
    const val ALLOWLIST = "settings/lab/mobileuse/allowlist"
    const val LOG = "settings/lab/mobileuse/log"
}

class MobileUseFeatureEntry @Inject constructor() : FeatureEntry {

    override val settingsItems: List<SettingsItem> = listOf(
        SettingsItem(
            route = MobileUseRoutes.LAB,
            group = SettingsGroup.LAB,
            order = 10,
            title = R.string.mobileuse_settings_title,
            icon = Icons.Outlined.TouchApp,
        ),
    )

    override fun NavGraphBuilder.routes(nav: NavController) {
        composable(MobileUseRoutes.LAB) {
            LabScreen(
                onBack = { nav.popBackStack() },
                onOpenRisk = { nav.navigate(MobileUseRoutes.RISK) },
                onOpenAllowlist = { nav.navigate(MobileUseRoutes.ALLOWLIST) },
                onOpenLog = { nav.navigate(MobileUseRoutes.LOG) },
            )
        }
        composable(MobileUseRoutes.RISK) {
            RiskDisclosureScreen(onBack = { nav.popBackStack() })
        }
        composable(MobileUseRoutes.ALLOWLIST) {
            AllowlistScreen(onBack = { nav.popBackStack() })
        }
        composable(MobileUseRoutes.LOG) {
            ActionLogScreen(onBack = { nav.popBackStack() })
        }
    }
}
