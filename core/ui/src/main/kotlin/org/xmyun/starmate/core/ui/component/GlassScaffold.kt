package org.xmyun.starmate.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.core.ui.glass.GlassState
import org.xmyun.starmate.core.ui.glass.LocalBottomBarInset
import org.xmyun.starmate.core.ui.glass.glass
import org.xmyun.starmate.core.ui.glass.glassSource
import org.xmyun.starmate.core.ui.glass.rememberGlassState

/**
 * 带毛玻璃栏的页面骨架。内容铺满整页，从标题栏和底栏下面滚过去，栏把滚到下面的内容糊成毛玻璃。
 *
 * content 收到的 PaddingValues 是各栏占掉的位置。要让内容从栏下面滚过，就把它当作滚动容器的
 * contentPadding（例如 LazyColumn(contentPadding = it)），不要用 Modifier.padding 把滚动容器整个缩进去。
 *
 * 底部留白已经包含了系统导航条和主框架的悬浮标签栏（见 [LocalBottomBarInset]）。
 * 自己带 bottomBar 的页面例外：这时底部留白是 bottomBar 的高度，bottomBar 要自己避开那两样。
 *
 * @param topBar 拿到的 [GlassState] 传给 [GlassTopBar] 或 Modifier.glass。
 * @param background 垫在内容下面的装饰，例如 [StarryWallpaper]。它也会被栏糊进去。
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable (GlassState) -> Unit = {},
    bottomBar: @Composable (GlassState) -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.surface,
    background: @Composable BoxScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val glass = rememberGlassState()
    Scaffold(
        modifier = modifier,
        topBar = { topBar(glass) },
        bottomBar = { bottomBar(glass) },
        floatingActionButton = floatingActionButton,
        snackbarHost = snackbarHost,
        containerColor = containerColor,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.add(WindowInsets(bottom = LocalBottomBarInset.current)),
    ) { padding ->
        Box(Modifier.fillMaxSize().glassSource(glass)) {
            background()
            content(padding)
        }
    }
}

/**
 * 毛玻璃标题栏：整条贴顶，下沿一条很淡的分隔线。
 *
 * @param below 标题下面、分隔线上面的附加内容，例如进度条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopBar(
    state: GlassState,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    below: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().glass(state)) {
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
        )
        below()
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}
