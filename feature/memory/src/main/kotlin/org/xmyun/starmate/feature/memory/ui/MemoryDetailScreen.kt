package org.xmyun.starmate.feature.memory.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import org.xmyun.starmate.core.ui.component.ConfirmDialog
import org.xmyun.starmate.core.ui.component.SettingsDivider
import org.xmyun.starmate.core.ui.component.SettingsRow
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.feature.memory.R
import org.xmyun.starmate.feature.memory.model.Categories
import org.xmyun.starmate.feature.memory.model.Mem
import org.xmyun.starmate.feature.memory.model.Sources

@Composable
fun MemoryDetailScreen(onBack: () -> Unit, viewModel: MemoryDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val memory = state.memory

    SettingsScaffold(title = stringResource(R.string.memory_detail_title), onBack = onBack) { padding ->
        if (memory == null) {
            if (state.loaded) {
                Text(
                    stringResource(R.string.memory_detail_missing),
                    modifier = Modifier.padding(padding).padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@SettingsScaffold
        }
        LazyColumn(contentPadding = padding) {
            item {
                SettingsSection(title = null) {
                    Text(
                        memory.content,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                    )
                }
            }
            item {
                SettingsSection(title = stringResource(R.string.memory_detail_info)) {
                    InfoRow(R.string.memory_field_category, categoryLabel(memory.category))
                    InfoRow(R.string.memory_field_importance, "${memory.importance} / 5")
                    InfoRow(R.string.memory_field_emotion, "${memory.emotion} / 3")
                    InfoRow(R.string.memory_field_source, sourceLabel(memory.source))
                    InfoRow(R.string.memory_field_status, statusLabel(memory))
                    memory.eventAt?.let { InfoRow(R.string.memory_field_event, formatTime(it)) }
                    InfoRow(R.string.memory_field_created, formatTime(memory.createdAt))
                    InfoRow(R.string.memory_field_updated, formatTime(memory.updatedAt))
                    memory.invalidAt?.let { InfoRow(R.string.memory_field_invalid, formatTime(it)) }
                }
            }
            item {
                SettingsSection(title = stringResource(R.string.memory_detail_origin)) {
                    val episode = state.episode
                    if (episode == null) {
                        Text(
                            stringResource(R.string.memory_source_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    } else {
                        Column(Modifier.padding(20.dp)) {
                            Text(formatTime(episode.ts), style = MaterialTheme.typography.labelMedium)
                            if (episode.userText.isNotBlank()) {
                                Text(stringResource(R.string.memory_episode_user), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                                Text(episode.userText, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (episode.replyText.isNotBlank()) {
                                Text(stringResource(R.string.memory_episode_reply), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                                Text(episode.replyText, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            item {
                SettingsSection(title = null) {
                    SettingsRow(icon = Icons.Outlined.Edit, title = stringResource(R.string.memory_edit), onClick = { editing = true })
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Outlined.PushPin,
                        title = stringResource(if (memory.pinned) R.string.memory_unpin else R.string.memory_pin),
                        summary = stringResource(R.string.memory_pin_summary),
                        onClick = { viewModel.setPinned(!memory.pinned) },
                    )
                    if (memory.archived || !memory.isValid) {
                        SettingsDivider()
                        SettingsRow(icon = Icons.Outlined.Restore, title = stringResource(R.string.memory_restore), onClick = viewModel::restore)
                    }
                    SettingsDivider()
                    SettingsRow(icon = Icons.Outlined.Delete, title = stringResource(R.string.memory_delete), onClick = { confirmDelete = true })
                }
            }
        }
    }

    if (editing && memory != null) {
        MemoryEditDialog(
            memory = memory,
            onSave = { content, importance ->
                viewModel.save(content, importance)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.memory_delete_confirm_title),
            text = stringResource(R.string.memory_delete_confirm_text),
            confirmLabel = stringResource(R.string.memory_delete),
            onConfirm = {
                confirmDelete = false
                viewModel.delete(onBack)
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
            icon = Icons.Outlined.DeleteForever,
        )
    }
}

@Composable
private fun InfoRow(label: Int, value: String) {
    SettingsRow(icon = null, title = stringResource(label), trailing = { Text(value, style = MaterialTheme.typography.bodyMedium) })
}

/** 修改一条记忆的文字和重要度。 */
@Composable
fun MemoryEditDialog(memory: Mem, onSave: (String, Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember(memory.id) { mutableStateOf(memory.content) }
    var importance by remember(memory.id) { mutableFloatStateOf(memory.importance.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(150) },
                    label = { Text(stringResource(R.string.memory_edit_content)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Text(
                    stringResource(R.string.memory_edit_importance, importance.roundToInt()),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Slider(value = importance, onValueChange = { importance = it }, valueRange = 1f..5f, steps = 3)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text, importance.roundToInt()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.memory_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memory_cancel)) } },
    )
}

@Composable
internal fun categoryLabel(category: String): String = stringResource(
    when (category) {
        Categories.PROFILE -> R.string.memory_cat_profile
        Categories.PREFERENCE -> R.string.memory_cat_preference
        Categories.RELATION -> R.string.memory_cat_relation
        Categories.SELF -> R.string.memory_cat_self
        Categories.FACT -> R.string.memory_cat_fact
        Categories.EVENT -> R.string.memory_cat_event
        Categories.INSIGHT -> R.string.memory_cat_insight
        Categories.FRAGMENT -> R.string.memory_cat_fragment
        else -> R.string.memory_cat_other
    },
)

@Composable
private fun sourceLabel(source: String): String = stringResource(
    when (source) {
        Sources.AI -> R.string.memory_source_ai
        Sources.MANUAL -> R.string.memory_source_manual
        Sources.REFLECTION -> R.string.memory_source_reflection
        else -> R.string.memory_source_user
    },
)

@Composable
private fun statusLabel(memory: Mem): String = stringResource(
    when {
        !memory.isValid -> R.string.memory_status_invalid
        memory.archived -> R.string.memory_status_archived
        memory.pinned -> R.string.memory_status_pinned
        else -> R.string.memory_status_active
    },
)

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun formatTime(ts: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
