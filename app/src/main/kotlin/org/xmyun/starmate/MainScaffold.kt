package org.xmyun.starmate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.xmyun.starmate.core.ui.component.EmptyState
import org.xmyun.starmate.core.ui.component.FloatingNavBar
import org.xmyun.starmate.core.ui.contract.BottomTab
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.core.ui.glass.LocalBottomBarInset
import org.xmyun.starmate.core.ui.glass.glassSource
import org.xmyun.starmate.core.ui.glass.rememberGlassState

/** 把各功能模块的标签和页面装进同一个导航宿主。 */
@Composable
fun MainScaffold(
    entries: Set<FeatureEntry>,
    pendingRoute: String?,
    onRouteConsumed: () -> Unit,
) {
    val tabs = remember(entries) { entries.flatMap { it.tabs }.sortedBy { it.order } }

    if (tabs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Outlined.Extension,
                title = stringResource(R.string.app_no_features_title),
                description = stringResource(R.string.app_no_features_description),
            )
        }
        return
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val tabRoutes = remember(tabs) { tabs.map { it.route }.toSet() }
    // 引导页、设置子页面等不是标签页，这时把底部栏收起来。
    val showBottomBar = currentDestination?.route in tabRoutes

    val openable = remember(entries) { openableRoutes(entries) }
    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            // MainActivity 对外可启动，route 可能来自别的应用：只认名单里的页面，其余一律忽略
            if (pendingRoute in openable) navController.navigateToTabOrRoute(pendingRoute, tabRoutes)
            onRouteConsumed()
        }
    }

    val navGlass = rememberGlassState()
    val density = LocalDensity.current
    var barInset by remember { mutableStateOf(DEFAULT_BAR_INSET) }
    val selectedRoute = tabs.firstOrNull { tab ->
        currentDestination?.hierarchy?.any { it.route == tab.route } == true
    }?.route

    // 页面铺满整个窗口，系统栏边距由各页面自己处理（它们都有自己的骨架）。
    // 标签栏浮在页面上面，把滚到它下面的内容糊成毛玻璃；它占掉的高度通过 LocalBottomBarInset 告诉页面，
    // 页面据此在底部多留一段，最后一项才滚得出来。
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        CompositionLocalProvider(LocalBottomBarInset provides if (showBottomBar) barInset else 0.dp) {
            NavHost(
                navController = navController,
                startDestination = tabs.first().route,
                modifier = Modifier.fillMaxSize().glassSource(navGlass),
            ) {
                entries.forEach { entry ->
                    with(entry) { routes(navController) }
                }
            }
        }
        AnimatedVisibility(
            visible = showBottomBar,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .onSizeChanged { barInset = with(density) { it.height.toDp() } }
                    .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                FloatingNavBar(
                    state = navGlass,
                    tabs = tabs,
                    selectedRoute = selectedRoute,
                    onSelect = { navController.navigateToTab(it) },
                )
            }
        }
    }
}

/** 标签栏量出实际高度之前先用的估计值：胶囊 64dp 加上下留白。 */
private val DEFAULT_BAR_INSET = 80.dp

private fun NavController.navigateToTab(tab: BottomTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * 外部 Intent（自己的通知，也可能是别的应用）要求打开的页面里，可以照办的那些：
 * 底部标签页，和设置首页上列出来的页面。它们都是不带参数的固定页面，打开本身不会触发任何操作。
 * 引导页、带参数的详情页不在其中。
 */
internal fun openableRoutes(entries: Set<FeatureEntry>): Set<String> =
    entries.flatMap { entry -> entry.tabs.map { it.route } + entry.settingsItems.map { it.route } }.toSet()

private fun NavController.navigateToTabOrRoute(route: String, tabRoutes: Set<String>) {
    try {
        navigate(route) {
            if (route in tabRoutes) {
                popUpTo(graph.findStartDestination().id) { saveState = true }
                restoreState = true
            }
            launchSingleTop = true
        }
    } catch (e: IllegalArgumentException) {
        // 名单里的页面没有注册成导航目的地（模块写错了）：留在当前页，不让一个 Intent 把应用弄崩
    }
}
