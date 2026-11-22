package org.xmyun.starmate.feature.memory

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import javax.inject.Inject
import org.xmyun.starmate.core.ui.contract.BottomTab
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.core.ui.contract.SettingsGroup
import org.xmyun.starmate.core.ui.contract.SettingsItem
import org.xmyun.starmate.core.ui.contract.StarmateRoutes
import org.xmyun.starmate.feature.memory.ui.MemoryDetailScreen
import org.xmyun.starmate.feature.memory.ui.MemoryDetailViewModel
import org.xmyun.starmate.feature.memory.ui.MemoryScreen
import org.xmyun.starmate.feature.memory.ui.MemorySettingsScreen
import org.xmyun.starmate.feature.memory.work.ConsolidateScheduler

class MemoryFeatureEntry @Inject constructor(
    scheduler: ConsolidateScheduler,
) : FeatureEntry {

    init {
        // 底座没有“App 启动”回调；入口在 MainActivity 创建时构造，借这里开始观察前后台、排好每晚任务
        scheduler.start()
    }

    override val tabs = listOf(
        BottomTab(route = StarmateRoutes.MEMORY, order = 2, label = R.string.memory_tab, icon = Icons.Outlined.Psychology),
    )

    override val settingsItems = listOf(
        SettingsItem(
            route = SETTINGS_ROUTE,
            group = SettingsGroup.ABILITIES,
            order = 10,
            title = R.string.memory_settings_title,
            icon = Icons.Outlined.Psychology,
        ),
    )

    override fun NavGraphBuilder.routes(nav: NavController) {
        composable(StarmateRoutes.MEMORY) {
            MemoryScreen(
                onOpen = { id -> nav.navigate("${StarmateRoutes.MEMORY}/$id") },
                onOpenSettings = { nav.navigate(SETTINGS_ROUTE) },
            )
        }
        composable(
            "${StarmateRoutes.MEMORY}/{${MemoryDetailViewModel.ARG_ID}}",
            arguments = listOf(navArgument(MemoryDetailViewModel.ARG_ID) { type = NavType.LongType }),
        ) {
            MemoryDetailScreen(onBack = { nav.popBackStack() })
        }
        composable(SETTINGS_ROUTE) {
            MemorySettingsScreen(onBack = { nav.popBackStack() })
        }
    }

    companion object {
        const val SETTINGS_ROUTE = "settings/memory"
    }
}
