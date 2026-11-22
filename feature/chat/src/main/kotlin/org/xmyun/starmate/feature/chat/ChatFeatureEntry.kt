package org.xmyun.starmate.feature.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.BugReport
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import javax.inject.Inject
import org.xmyun.starmate.core.ui.contract.BottomTab
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.core.ui.contract.SettingsGroup
import org.xmyun.starmate.core.ui.contract.SettingsItem
import org.xmyun.starmate.feature.chat.chat.ChatScreen
import org.xmyun.starmate.feature.chat.common.ChatRoutes
import org.xmyun.starmate.feature.chat.onboarding.onboardingRoutes
import org.xmyun.starmate.feature.chat.onboarding.startOnboarding
import org.xmyun.starmate.feature.chat.settings.AppearanceScreen
import org.xmyun.starmate.feature.chat.settings.CompanionEditScreen
import org.xmyun.starmate.feature.chat.settings.ConsentsScreen
import org.xmyun.starmate.feature.chat.settings.DataScreen
import org.xmyun.starmate.feature.chat.settings.DiagScreen
import org.xmyun.starmate.feature.chat.settings.ModelsScreen
import org.xmyun.starmate.feature.chat.settings.ProviderEditorScreen
import org.xmyun.starmate.feature.chat.settings.SettingsScreen
import org.xmyun.starmate.feature.chat.settings.UsageScreen

/** 聊天和设置两个标签、本模块的设置项和全部页面。 */
class ChatFeatureEntry @Inject constructor() : FeatureEntry {

    override val tabs: List<BottomTab> = listOf(
        BottomTab(ChatRoutes.CHAT, order = 0, label = R.string.chat_tab_chat, icon = Icons.Outlined.ChatBubbleOutline),
        BottomTab(ChatRoutes.SETTINGS, order = 3, label = R.string.chat_tab_settings, icon = Icons.Outlined.Settings),
    )

    override val settingsItems: List<SettingsItem> = listOf(
        SettingsItem(ChatRoutes.SETTINGS_COMPANION, SettingsGroup.COMPANION, 10, R.string.chat_companion_title, Icons.Outlined.Face),
        SettingsItem(ChatRoutes.SETTINGS_APPEARANCE, SettingsGroup.COMPANION, 20, R.string.chat_appearance_title, Icons.Outlined.Palette),
        SettingsItem(ChatRoutes.SETTINGS_MODELS, SettingsGroup.MODELS, 10, R.string.chat_models_title, Icons.Outlined.Hub),
        SettingsItem(ChatRoutes.SETTINGS_CONSENTS, SettingsGroup.ABILITIES, 90, R.string.chat_consents_title, Icons.Outlined.TaskAlt),
        SettingsItem(ChatRoutes.SETTINGS_USAGE, SettingsGroup.DATA, 10, R.string.chat_usage_title, Icons.Outlined.QueryStats),
        SettingsItem(ChatRoutes.SETTINGS_DATA, SettingsGroup.DATA, 20, R.string.chat_data_title, Icons.Outlined.Inventory2),
        SettingsItem(ChatRoutes.SETTINGS_DIAG, SettingsGroup.DATA, 30, R.string.chat_diag_title, Icons.Outlined.BugReport),
    )

    override fun NavGraphBuilder.routes(nav: NavController) {
        composable(ChatRoutes.CHAT) {
            ChatScreen(
                viewModel = hiltViewModel(),
                onStartOnboarding = { route -> nav.startOnboarding(route) },
                onNavigate = { route -> nav.navigate(route) },
                onShowPermissionInvites = { nav.navigate(ChatRoutes.ONBOARDING_PERMISSIONS) { launchSingleTop = true } },
            )
        }
        onboardingRoutes(nav)

        composable(ChatRoutes.SETTINGS) {
            SettingsScreen(viewModel = hiltViewModel(), onOpen = { route -> nav.navigate(route) })
        }
        composable(ChatRoutes.SETTINGS_MODELS) {
            ModelsScreen(
                viewModel = hiltViewModel(),
                onBack = { nav.popBackStack() },
                onEditProvider = { id -> nav.navigate(ChatRoutes.provider(id)) },
            )
        }
        composable(ChatRoutes.SETTINGS_PROVIDER) {
            ProviderEditorScreen(viewModel = hiltViewModel(), onBack = { nav.popBackStack() })
        }
        composable(ChatRoutes.SETTINGS_COMPANION) {
            CompanionEditScreen(viewModel = hiltViewModel(), onBack = { nav.popBackStack() })
        }
        composable(ChatRoutes.SETTINGS_APPEARANCE) {
            AppearanceScreen(viewModel = hiltViewModel(), onBack = { nav.popBackStack() })
        }
        composable(ChatRoutes.SETTINGS_USAGE) {
            UsageScreen(viewModel = hiltViewModel(), onBack = { nav.popBackStack() })
        }
        composable(ChatRoutes.SETTINGS_DATA) {
            DataScreen(viewModel = hiltViewModel(), onBack = { nav.popBackStack() })
        }
        composable(ChatRoutes.SETTINGS_DIAG) {
            DiagScreen(viewModel = hiltViewModel(), onBack = { nav.popBackStack() })
        }
        composable(ChatRoutes.SETTINGS_CONSENTS) {
            ConsentsScreen(viewModel = hiltViewModel(), onBack = { nav.popBackStack() })
        }
    }
}
