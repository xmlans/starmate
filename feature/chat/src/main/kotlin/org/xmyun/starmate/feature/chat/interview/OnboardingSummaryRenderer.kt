package org.xmyun.starmate.feature.chat.interview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import javax.inject.Inject
import javax.inject.Singleton
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.AttachmentTypes
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.ui.contract.AttachmentRenderer
import org.xmyun.starmate.core.ui.theme.CardShape
import org.xmyun.starmate.feature.chat.R

/** “我记住了这些”小结卡片。暂存期间可以逐条修改、删除，点确认后落盘；落盘后只读。 */
@Singleton
class OnboardingSummaryRenderer @Inject constructor(
    private val coordinator: InterviewCoordinator,
) : AttachmentRenderer {

    override val type: String = AttachmentTypes.ONBOARDING_SUMMARY

    @Composable
    override fun Render(attachment: Attachment, message: ChatMessage, modifier: Modifier) {
        val pending by coordinator.pendingProfile.collectAsState(initial = null)
        val confirmed = InterviewCoordinator.isConfirmed(attachment)
        val editable = !confirmed && pending != null
        val profile = if (editable) pending!! else InterviewCoordinator.profileOf(attachment)
        SummaryCard(
            profile = profile,
            editable = editable,
            confirmed = confirmed,
            onChange = { coordinator.updatePendingAsync(it) },
            onConfirm = { coordinator.confirmAsync() },
            modifier = modifier,
        )
    }
}

@Composable
private fun SummaryCard(
    profile: OnboardingProfile,
    editable: Boolean,
    confirmed: Boolean,
    onChange: (OnboardingProfile) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier,
) {
    var editing by remember { mutableStateOf<SummaryItem?>(null) }
    val items = remember(profile) { SummaryItems.of(profile) }

    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.chat_summary_title), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.padding(top = 8.dp))
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.chat_summary_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text(
                            stringResource(fieldLabel(item.field)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(displayValue(item), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (editable) {
                        IconButton(onClick = { editing = item }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.chat_summary_edit), modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onChange(SummaryItems.remove(profile, item)) }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_summary_remove), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.padding(top = 8.dp))
            when {
                editable -> {
                    Text(
                        stringResource(R.string.chat_summary_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.padding(top = 8.dp))
                    FilledTonalButton(onClick = onConfirm, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.chat_summary_confirm))
                    }
                }
                confirmed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.chat_summary_confirmed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    editing?.let { item ->
        if (item.field == SummaryField.PROACTIVITY) {
            ProactivityDialog(
                current = item.value,
                onPick = { value ->
                    onChange(SummaryItems.edit(profile, item, value))
                    editing = null
                },
                onDismiss = { editing = null },
            )
        } else {
            EditItemDialog(
                item = item,
                onSave = { value ->
                    onChange(SummaryItems.edit(profile, item, value))
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
    }
}

@Composable
private fun displayValue(item: SummaryItem): String =
    if (item.field == SummaryField.PROACTIVITY) {
        stringResource(proactivityLabel(item.value))
    } else {
        item.value
    }

private fun proactivityLabel(value: String): Int = when (value) {
    OnboardingProfile.OFTEN -> R.string.chat_proactivity_often
    OnboardingProfile.NEVER -> R.string.chat_proactivity_never
    else -> R.string.chat_proactivity_sometimes
}

private fun fieldLabel(field: SummaryField): Int = when (field) {
    SummaryField.NICKNAME -> R.string.chat_field_nickname
    SummaryField.BIRTHDAY -> R.string.chat_field_birthday
    SummaryField.SLEEP_TIME -> R.string.chat_field_sleep_time
    SummaryField.WAKE_TIME -> R.string.chat_field_wake_time
    SummaryField.OCCUPATION -> R.string.chat_field_occupation
    SummaryField.LIKES -> R.string.chat_field_likes
    SummaryField.DISLIKES -> R.string.chat_field_dislikes
    SummaryField.PERSONA -> R.string.chat_field_persona
    SummaryField.PROACTIVITY -> R.string.chat_field_proactivity
    SummaryField.AVOID_TOPICS -> R.string.chat_field_avoid_topics
    SummaryField.OTHER_FACTS -> R.string.chat_field_other_facts
}

@Composable
private fun EditItemDialog(item: SummaryItem, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(item) { mutableStateOf(item.value) }
    val isTime = item.field == SummaryField.SLEEP_TIME || item.field == SummaryField.WAKE_TIME
    val timeInvalid = isTime && text.isNotBlank() && OnboardingProfile.normalizeTime(text) == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(fieldLabel(item.field))) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = isTime,
                isError = timeInvalid,
                supportingText = if (isTime) {
                    { Text(stringResource(R.string.chat_summary_time_hint)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = !timeInvalid) { Text(stringResource(R.string.chat_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(org.xmyun.starmate.core.ui.R.string.core_cancel)) }
        },
    )
}

@Composable
private fun ProactivityDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(OnboardingProfile.OFTEN, OnboardingProfile.SOMETIMES, OnboardingProfile.NEVER)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_field_proactivity)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == current, role = Role.RadioButton, onClick = { onPick(option) })
                            .padding(vertical = 8.dp),
                    ) {
                        RadioButton(selected = option == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(proactivityLabel(option)))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(org.xmyun.starmate.core.ui.R.string.core_cancel)) }
        },
    )
}
