package org.xmyun.starmate.feature.mcp

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import javax.inject.Inject
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.core.ui.contract.SettingsGroup
import org.xmyun.starmate.core.ui.contract.SettingsItem
import org.xmyun.starmate.feature.mcp.ui.McpSettingsScreen

class McpFeatureEntry @Inject constructor() : FeatureEntry {

    override val settingsItems = listOf(
        SettingsItem(
            route = ROUTE,
            group = SettingsGroup.ABILITIES,
            order = 50,
            title = R.string.mcp_settings_title,
            icon = Icons.Outlined.Hub,
        ),
    )

    override fun NavGraphBuilder.routes(nav: NavController) {
        composable(ROUTE) { McpSettingsScreen(onBack = { nav.popBackStack() }) }
    }

    companion object {
        const val ROUTE = "settings/mcp"
    }
}
