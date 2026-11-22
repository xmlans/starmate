package org.xmyun.starmate.feature.chat.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.core.model.AppEdition
import org.xmyun.starmate.feature.chat.R

/** 举报原因。 */
enum class ReportReason(@StringRes val label: Int) {
    OFFENSIVE(R.string.chat_report_reason_offensive),
    SEXUAL(R.string.chat_report_reason_sexual),
    HARMFUL(R.string.chat_report_reason_harmful),
    OTHER(R.string.chat_report_reason_other),
}

/**
 * 举报她的一条回复：选原因，可选“同时发给开发者”。确认后由调用方删除这条回复，
 * 需要时再用 [ReportMail.open] 打开邮件应用。
 */
@Composable
fun ReportDialog(
    onConfirm: (reason: ReportReason, sendToDeveloper: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf(ReportReason.OFFENSIVE) }
    var send by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Flag, contentDescription = null) },
        title = { Text(stringResource(R.string.chat_report_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.chat_report_intro), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.selectableGroup().padding(top = 4.dp)) {
                    ReportReason.entries.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = reason == option, role = Role.RadioButton, onClick = { reason = option })
                                .padding(vertical = 6.dp),
                        ) {
                            RadioButton(selected = reason == option, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(option.label), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = send, role = Role.Checkbox, onValueChange = { send = it })
                        .padding(vertical = 6.dp),
                ) {
                    Checkbox(checked = send, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.chat_report_send), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason, send) }) {
                Text(stringResource(R.string.chat_report_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(org.xmyun.starmate.core.ui.R.string.core_cancel)) }
        },
    )
}

/** 把举报写成一封邮件交给邮件应用。不直接发送：用户在邮件应用里看过内容再决定发不发。 */
object ReportMail {

    /** 打开邮件应用；没有邮件应用时返回 false。 */
    fun open(context: Context, edition: AppEdition, reason: ReportReason, reply: String): Boolean {
        val body = context.getString(
            R.string.chat_report_email_body,
            context.getString(reason.label),
            reply,
            edition.versionName,
            edition.id,
        )
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(edition.contactEmail))
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.chat_report_email_subject))
            .putExtra(Intent.EXTRA_TEXT, body)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
