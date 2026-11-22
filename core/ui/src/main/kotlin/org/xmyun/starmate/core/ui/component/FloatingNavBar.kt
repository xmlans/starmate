package org.xmyun.starmate.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.core.ui.contract.BottomTab
import org.xmyun.starmate.core.ui.glass.GlassState
import org.xmyun.starmate.core.ui.glass.glass
import org.xmyun.starmate.core.ui.theme.PillShape

/**
 * 悬浮的底部标签栏：一条毛玻璃胶囊，浮在内容上面，当前标签有一块主题色的底。
 *
 * 只画胶囊本身。贴底、避开系统导航条、左右留边由调用方用 modifier 处理。
 *
 * @param state 被它盖住的内容对应的 [GlassState]。
 * @param selectedRoute 当前标签的 route；不在 tabs 里时没有标签被选中。
 */
@Composable
fun FloatingNavBar(
    state: GlassState,
    tabs: List<BottomTab>,
    selectedRoute: String?,
    onSelect: (BottomTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 64.dp)
            .glass(state, shape = PillShape, elevation = 10.dp)
            .selectableGroup()
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            NavItem(
                tab = tab,
                selected = tab.route == selectedRoute,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun NavItem(tab: BottomTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val highlight by animateColorAsState(
        if (selected) colors.primaryContainer.copy(alpha = 0.9f) else Color.Transparent,
        label = "navHighlight",
    )
    val content by animateColorAsState(
        if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
        label = "navContent",
    )
    Box(
        modifier = modifier
            .clip(PillShape)
            .background(highlight)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(tab.icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
            Text(
                text = stringResource(tab.label),
                style = MaterialTheme.typography.labelMedium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
