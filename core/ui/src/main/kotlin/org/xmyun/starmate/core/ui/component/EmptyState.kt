package org.xmyun.starmate.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.core.ui.theme.StarmateTheme

/**
 * 空状态：圆形浅色底上的图标、标题、可选说明和可选操作按钮，在可用空间里居中。
 * 默认占满父布局；放在 LazyColumn 里时会退化为按内容高度显示。
 *
 * @param action 可选的操作区，例如一个 FilledTonalButton。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (description != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

@PreviewLightDark
@Composable
private fun EmptyStatePreview() {
    StarmateTheme {
        Surface {
            EmptyState(
                icon = Icons.Outlined.AutoStories,
                title = "还没有日记",
                description = "多聊几天，她会在晚上悄悄写下和你有关的事。",
                action = { FilledTonalButton(onClick = {}) { Text("去聊天") } },
            )
        }
    }
}
