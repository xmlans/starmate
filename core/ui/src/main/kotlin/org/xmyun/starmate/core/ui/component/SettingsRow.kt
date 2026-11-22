package org.xmyun.starmate.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.core.ui.theme.StarmateTheme

private const val DISABLED_ALPHA = 0.38f

/**
 * 设置列表的一行：左侧图标（放在 12dp 圆角的浅色底块里）、标题、可选摘要、右侧可选内容。
 * 整行是一个点击目标，高度至少 56dp，随系统字体放大自动增高。
 *
 * @param icon 为 null 时不显示左侧图标。图标是装饰，读屏读的是标题和摘要。
 * @param onClick 为 null 时整行不可点（只展示信息）。
 * @param trailing 右侧内容，例如 [SettingsChevron]、当前值文字。要放开关请用 [SettingsSwitchRow]。
 */
@Composable
fun SettingsRow(
    icon: ImageVector?,
    title: String,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }
    SettingsListItem(
        icon = icon,
        title = title,
        summary = summary,
        enabled = enabled,
        trailing = trailing,
        modifier = modifier.then(clickModifier),
    )
}

/**
 * 带开关的设置行。整行都能切换开关，读屏会把它读作一个开关。
 */
@Composable
fun SettingsSwitchRow(
    icon: ImageVector?,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SettingsListItem(
        icon = icon,
        title = title,
        summary = summary,
        enabled = enabled,
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    )
}

/** 设置行右侧的“进入下一页”箭头。纯装饰，不带无障碍描述。 */
@Composable
fun SettingsChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 一组设置：可选的小标题 + 一张 20dp 圆角的卡片，卡片里依次放 [SettingsRow] 等。
 * 行与行之间不自动加分隔线；需要时调用方自己放 [SettingsDivider]。
 */
@Composable
fun SettingsSection(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .semantics { heading() },
            )
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

/** 分组卡片里两行之间的细分隔线，左侧与标题文字对齐。 */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = 72.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}

@Composable
private fun SettingsListItem(
    icon: ImageVector?,
    title: String,
    summary: String?,
    enabled: Boolean,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    ListItem(
        headlineContent = { Text(title) },
        modifier = modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = icon?.let { { SettingsIcon(it) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SettingsIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(22.dp),
        )
    }
}

@PreviewLightDark
@Composable
private fun SettingsRowPreview() {
    StarmateTheme {
        Surface {
            Column(Modifier.padding(vertical = 8.dp)) {
                SettingsSection(title = "陪伴") {
                    SettingsRow(
                        icon = Icons.Outlined.Favorite,
                        title = "她的资料",
                        summary = "名字、头像、人设",
                        onClick = {},
                        trailing = { SettingsChevron() },
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = Icons.Outlined.Notifications,
                        title = "主动消息",
                        summary = "她会偶尔来找你聊天",
                        checked = true,
                        onCheckedChange = {},
                    )
                }
                SettingsSection(title = null) {
                    SettingsRow(
                        icon = Icons.Outlined.Storage,
                        title = "导出数据",
                        summary = "暂不可用",
                        onClick = {},
                        enabled = false,
                    )
                    SettingsRow(icon = null, title = "版本", trailing = { Text("1.0.0") })
                }
            }
        }
    }
}
