package org.xmyun.starmate.feature.diary

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
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
import org.xmyun.starmate.feature.diary.ui.DiaryEntryScreen
import org.xmyun.starmate.feature.diary.ui.DiaryEntryViewModel
import org.xmyun.starmate.feature.diary.ui.DiaryScreen
import org.xmyun.starmate.feature.diary.ui.DiarySettingsScreen
import org.xmyun.starmate.feature.diary.work.DiaryScheduler

class DiaryFeatureEntry @Inject constructor(scheduler: DiaryScheduler) : FeatureEntry {

    init {
        // 底座没有“App 启动”回调；入口在 MainActivity 创建时构造，借这里确保有一个日记任务在排队
        try {
            scheduler.ensureScheduled()
        } catch (e: IllegalStateException) {
            // WorkManager 未初始化（例如测试环境）时跳过
        }
    }

    override val tabs = listOf(
        BottomTab(route = StarmateRoutes.DIARY, order = 1, label = R.string.diary_tab, icon = Icons.Outlined.AutoStories),
    )

    override val settingsItems = listOf(
        SettingsItem(
            route = SETTINGS_ROUTE,
            group = SettingsGroup.ABILITIES,
            order = 15,
            title = R.string.diary_settings_title,
            icon = Icons.Outlined.AutoStories,
        ),
    )

    override fun NavGraphBuilder.routes(nav: NavController) {
        composable(StarmateRoutes.DIARY) {
            DiaryScreen(
                onOpen = { id -> nav.navigate("${StarmateRoutes.DIARY}/$id") },
                onOpenSettings = { nav.navigate(SETTINGS_ROUTE) },
            )
        }
        composable(
            "${StarmateRoutes.DIARY}/{${DiaryEntryViewModel.ARG_ID}}",
            arguments = listOf(navArgument(DiaryEntryViewModel.ARG_ID) { type = NavType.LongType }),
        ) {
            DiaryEntryScreen(
                onBack = { nav.popBackStack() },
                onReply = { nav.navigate(StarmateRoutes.CHAT) { launchSingleTop = true } },
            )
        }
        composable(SETTINGS_ROUTE) {
            DiarySettingsScreen(onBack = { nav.popBackStack() })
        }
    }

    companion object {
        const val SETTINGS_ROUTE = "settings/diary"
    }
}
