package org.xmyun.starmate.core.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import org.xmyun.starmate.core.ui.R
import org.xmyun.starmate.core.ui.theme.StarmateTheme

/**
 * 设置类页面的骨架：毛玻璃标题栏（可选返回键）+ 内容区。
 * 内容从标题栏下面滚过去。content 收到的 PaddingValues 必须用在内容上
 * （例如 LazyColumn(contentPadding = it)），否则内容会被标题栏挡住。
 *
 * @param onBack 为 null 时不显示返回键（例如底部标签里的“设置”首页）。
 * @param backContentDescription 返回键的无障碍描述，默认“返回”。
 */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backContentDescription: String = stringResource(R.string.core_back),
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    GlassScaffold(
        modifier = modifier,
        topBar = { glass ->
            GlassTopBar(
                state = glass,
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backContentDescription)
                        }
                    }
                },
                actions = actions,
            )
        },
        floatingActionButton = floatingActionButton,
        snackbarHost = snackbarHost,
        content = content,
    )
}

@PreviewLightDark
@Composable
private fun SettingsScaffoldPreview() {
    StarmateTheme {
        SettingsScaffold(title = "外观", onBack = {}) { padding ->
            LazyColumn(contentPadding = padding) {
                item {
                    SettingsSection(title = "主题") {
                        SettingsRow(icon = Icons.Outlined.Palette, title = "主题色", summary = "浅粉", onClick = {})
                        SettingsSwitchRow(
                            icon = Icons.Outlined.DarkMode,
                            title = "跟随系统深色模式",
                            checked = true,
                            onCheckedChange = {},
                        )
                    }
                }
            }
        }
    }
}
