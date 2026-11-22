package org.xmyun.starmate.feature.diary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xmyun.starmate.core.ui.component.ConfirmDialog
import org.xmyun.starmate.core.ui.component.EmptyState
import org.xmyun.starmate.core.ui.component.SettingsDivider
import org.xmyun.starmate.core.ui.component.SettingsRow
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.core.ui.component.SettingsSwitchRow
import org.xmyun.starmate.core.ui.theme.CardShape
import org.xmyun.starmate.feature.diary.R
import org.xmyun.starmate.feature.diary.db.DiaryEntryEntity
import org.xmyun.starmate.feature.diary.write.DiaryFrequency

/** 日记时间线：日期倒序，未读的带红点。 */
@Composable
fun DiaryScreen(onOpen: (Long) -> Unit, onOpenSettings: () -> Unit, viewModel: DiaryListViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    SettingsScaffold(
        title = stringResource(R.string.diary_title),
        onBack = null,
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.diary_settings_title))
            }
        },
    ) { padding ->
        val list = entries
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState(
                icon = Icons.Outlined.AutoStories,
                title = stringResource(R.string.diary_empty_title),
                description = stringResource(R.string.diary_empty_desc),
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
            ) {
                items(list, key = { it.id }) { entry -> DiaryRow(entry, onClick = { onOpen(entry.id) }) }
            }
        }
    }
}

@Composable
private fun DiaryRow(entry: DiaryEntryEntity, onClick: () -> Unit) {
    val unread = stringResource(R.string.diary_unread)
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(CardShape).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (entry.mood.isNotBlank()) {
                    Text(entry.mood, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (!entry.read) {
                Spacer(Modifier.size(8.dp))
                Box(
                    Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape)
                        .semantics { this.contentDescription = unread },
                )
            }
        }
    }
}

/** 日记正文。打开即标为已读；“回复这篇”带着引用跳到聊天。 */
@Composable
fun DiaryEntryScreen(onBack: () -> Unit, onReply: () -> Unit, viewModel: DiaryEntryViewModel = hiltViewModel()) {
    val entry by viewModel.entry.collectAsStateWithLifecycle()
    val previewFormat = stringResource(R.string.diary_quote_preview)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold(
        title = entry?.title ?: stringResource(R.string.diary_title),
        onBack = onBack,
        actions = {
            if (entry != null) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.diary_delete))
                }
            }
        },
    ) { padding ->
        val current = entry ?: return@SettingsScaffold
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 正文写在一张白色的“纸”上
            Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        listOf(current.date, current.mood).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(current.body, style = MaterialTheme.typography.bodyLarge)
                }
            }
            FilledTonalButton(
                onClick = {
                    viewModel.reply(current, previewFormat)
                    onReply()
                },
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Reply, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.diary_reply))
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.diary_delete_confirm_title),
            text = stringResource(R.string.diary_delete_confirm_text),
            confirmLabel = stringResource(R.string.diary_delete),
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
fun DiarySettingsScreen(onBack: () -> Unit, viewModel: DiarySettingsViewModel = hiltViewModel()) {
    val frequency by viewModel.frequency.collectAsStateWithLifecycle()
    val useChat by viewModel.useChatModel.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(title = stringResource(R.string.diary_settings_title), onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                SettingsSection(title = stringResource(R.string.diary_frequency)) {
                    val options = listOf(
                        DiaryFrequency.OFF to R.string.diary_frequency_off,
                        DiaryFrequency.SOMETIMES to R.string.diary_frequency_sometimes,
                        DiaryFrequency.OFTEN to R.string.diary_frequency_often,
                    )
                    options.forEachIndexed { index, (value, label) ->
                        if (index > 0) SettingsDivider()
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = frequency == value, role = Role.RadioButton, onClick = { viewModel.setFrequency(value) })
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = frequency == value, onClick = null)
                            Spacer(Modifier.size(16.dp))
                            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            item {
                SettingsSection(title = stringResource(R.string.diary_model)) {
                    SettingsSwitchRow(
                        icon = null,
                        title = stringResource(R.string.diary_model_chat),
                        summary = stringResource(if (useChat) R.string.diary_model_chat_on else R.string.diary_model_chat_off),
                        checked = useChat,
                        onCheckedChange = viewModel::setUseChatModel,
                    )
                }
            }
            item {
                SettingsSection(title = null) {
                    SettingsRow(
                        icon = Icons.Outlined.EditNote,
                        title = stringResource(R.string.diary_write_now),
                        summary = stringResource(R.string.diary_write_now_summary),
                        onClick = viewModel::writeNow,
                        enabled = !state.busy,
                    )
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 32.dp))
                state.message?.let {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}
