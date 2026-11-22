package org.xmyun.starmate.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import org.xmyun.starmate.core.ui.R
import org.xmyun.starmate.core.ui.theme.StarmateTheme

/**
 * 确认对话框。点确认只回调 onConfirm，不会自动关闭；调用方在 onConfirm 里执行操作并收起对话框。
 * 点取消、返回键或对话框外部都会回调 onDismiss。正文过长（或系统字体很大）时可滚动。
 *
 * @param destructive 为 true 时确认按钮用错误色，用于删除、清空等不可撤销的操作。
 * @param dismissLabel 取消按钮文字，默认“取消”。
 * @param icon 可选的顶部图标，纯装饰。
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = stringResource(R.string.core_cancel),
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(confirmLabel) }
        },
        modifier = modifier,
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        title = { Text(title) },
        text = {
            Box(Modifier.verticalScroll(rememberScrollState())) {
                Text(text)
            }
        },
    )
}

@PreviewLightDark
@Composable
private fun ConfirmDialogPreview() {
    StarmateTheme {
        Surface {
            ConfirmDialog(
                title = "清空记忆？",
                text = "她会忘记你们之间的所有事情，聊天记录会保留。此操作无法撤销。",
                confirmLabel = "清空",
                onConfirm = {},
                onDismiss = {},
                destructive = true,
                icon = Icons.Outlined.DeleteForever,
            )
        }
    }
}
