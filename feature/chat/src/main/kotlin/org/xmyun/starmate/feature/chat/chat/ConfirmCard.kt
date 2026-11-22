package org.xmyun.starmate.feature.chat.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.core.model.ConfirmRequest
import org.xmyun.starmate.core.ui.theme.CardShape
import org.xmyun.starmate.feature.chat.R

/** 她要执行一个会改动外部数据的操作，先问用户。 */
@Composable
fun ConfirmCard(
    request: ConfirmRequest,
    onApprove: (remember: Boolean) -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CardShape,
        color = colors.secondaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.chat_confirm_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSecondaryContainer,
                )
            }
            Spacer(Modifier.padding(top = 8.dp))
            // 说明可能很长（例如列出全部参数和网址）：限高并可滚动，同意和拒绝的按钮始终留在屏幕上
            Box(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                Text(request.summary, style = MaterialTheme.typography.bodyLarge, color = colors.onSecondaryContainer)
            }
            Spacer(Modifier.padding(top = 12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(onClick = onDeny) { Text(stringResource(R.string.chat_confirm_deny)) }
                Button(onClick = { onApprove(false) }) { Text(stringResource(R.string.chat_confirm_approve)) }
            }
            if (request.allowRemember) {
                TextButton(onClick = { onApprove(true) }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.chat_confirm_approve_remember))
                }
            }
        }
    }
}
