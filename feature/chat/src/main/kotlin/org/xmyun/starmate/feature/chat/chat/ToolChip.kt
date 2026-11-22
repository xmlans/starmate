package org.xmyun.starmate.feature.chat.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.core.ui.theme.StarmateRadius

/** 工具活动小标签：进行中显示小圈，结束显示对勾或感叹号。 */
@Composable
fun ToolChip(activity: ToolActivity, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(StarmateRadius.Small),
        color = colors.secondaryContainer.copy(alpha = 0.92f),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            when {
                activity.running -> CircularProgressIndicator(
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(12.dp),
                    color = colors.onSecondaryContainer,
                )
                activity.isError -> Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.error, modifier = Modifier.size(14.dp))
                else -> Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = activity.summary.ifBlank { activity.name },
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
