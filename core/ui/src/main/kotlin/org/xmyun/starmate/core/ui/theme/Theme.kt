package org.xmyun.starmate.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 星伴主题。浅色 / 深色默认跟随系统，主题色默认读 ThemePrefs。
 * 不使用动态取色（Material You），保持星伴自己的配色。
 *
 * @param darkTheme 传 true / false 可强制深色或浅色。
 * @param accent 传入时不再读 ThemePrefs，用于预览和主题色选择页的示意。
 */
@Composable
fun StarmateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: ThemeAccent = rememberThemeAccent(),
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(accent, darkTheme) { starmateColorScheme(accent, darkTheme) }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = StarmateTypography,
        shapes = StarmateShapes,
        content = content,
    )
}

/** 读取并订阅用户选的主题色。预览环境里直接返回默认浅粉，不碰磁盘。 */
@Composable
fun rememberThemeAccent(): ThemeAccent {
    if (LocalInspectionMode.current) return ThemeAccent.Default
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { ThemePrefs(context) }
    // 首帧同步读一次（一个很小的文件，DataStore 读过后常驻内存），避免冷启动先闪一下浅粉再变成用户选的颜色。
    val initial = remember(prefs) { runBlocking { prefs.accent.first() } }
    val accent by prefs.accent.collectAsState(initial = initial)
    return accent
}

@PreviewLightDark
@Composable
private fun ThemeAccentsPreview() {
    StarmateTheme {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeAccent.entries.forEach { accent ->
                    val scheme = starmateColorScheme(accent, isSystemInDarkTheme())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(
                            accent.seed,
                            scheme.primary,
                            scheme.primaryContainer,
                            scheme.secondaryContainer,
                            scheme.tertiaryContainer,
                            scheme.surfaceContainerHigh,
                        ).forEach { color ->
                            Box(
                                Modifier
                                    .padding(end = 6.dp)
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(color),
                            )
                        }
                        Text(
                            text = stringResource(accent.label),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
