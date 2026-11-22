package org.xmyun.starmate.feature.chat.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.core.ui.component.GlassScaffold
import org.xmyun.starmate.core.ui.component.GlassTopBar
import org.xmyun.starmate.core.ui.component.StarryWallpaper
import org.xmyun.starmate.core.ui.glass.glass

/**
 * 引导页的统一骨架：可选返回键、步骤提示、大标题、说明、内容区，底部固定的按钮区。
 * 内容区可滚动，从毛玻璃的标题栏和按钮区下面滚过；底部按钮随输入法上移，收起时避开系统导航条。
 *
 * @param wallpaper 用星空壁纸做底。只给没有表单的页面用（欢迎页），这时正文里的成段文字要自己放进卡片。
 */
@Composable
fun OnboardingPage(
    step: Int?,
    title: String,
    description: String?,
    onBack: (() -> Unit)?,
    bottom: @Composable ColumnScope.() -> Unit,
    wallpaper: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassScaffold(
        background = { if (wallpaper) StarryWallpaper() },
        topBar = { glass ->
            GlassTopBar(
                state = glass,
                title = {
                    if (step != null) {
                        Text(
                            stringResource(org.xmyun.starmate.feature.chat.R.string.chat_onboarding_step, step, TOTAL_STEPS),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(org.xmyun.starmate.core.ui.R.string.core_back),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = { glass ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .glass(glass)
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = bottom,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (description != null) {
                Spacer(Modifier.height(12.dp))
                Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

private const val TOTAL_STEPS = 3
