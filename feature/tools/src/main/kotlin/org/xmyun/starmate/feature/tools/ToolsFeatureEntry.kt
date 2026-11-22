package org.xmyun.starmate.feature.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Widgets
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import javax.inject.Inject
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.core.ui.contract.SettingsGroup
import org.xmyun.starmate.core.ui.contract.SettingsItem
import org.xmyun.starmate.feature.tools.ui.GroupDetailScreen
import org.xmyun.starmate.feature.tools.ui.GroupDetailViewModel
import org.xmyun.starmate.feature.tools.ui.RestrictedSettingsGuideScreen
import org.xmyun.starmate.feature.tools.ui.ToolsSettingsScreen

class ToolsFeatureEntry @Inject constructor() : FeatureEntry {

    override val settingsItems = listOf(
        SettingsItem(
            route = ROUTE,
            group = SettingsGroup.ABILITIES,
            order = 40,
            title = R.string.tools_settings_title,
            icon = Icons.Outlined.Widgets,
        ),
    )

    override fun NavGraphBuilder.routes(nav: NavController) {
        composable(ROUTE) {
            ToolsSettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenGroup = { nav.navigate("$ROUTE/group/${it.key}") },
            )
        }
        composable(
            route = "$ROUTE/group/{${GroupDetailViewModel.ARG_GROUP}}",
            arguments = listOf(navArgument(GroupDetailViewModel.ARG_GROUP) { type = NavType.StringType }),
        ) {
            GroupDetailScreen(
                onBack = { nav.popBackStack() },
                onOpenRestrictedGuide = { nav.navigate(RESTRICTED_ROUTE) },
            )
        }
        composable(RESTRICTED_ROUTE) {
            RestrictedSettingsGuideScreen(onBack = { nav.popBackStack() })
        }
    }

    companion object {
        const val ROUTE = "settings/tools"
        const val RESTRICTED_ROUTE = "settings/tools/restricted"
    }
}
