package org.xmyun.starmate.feature.memory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xmyun.starmate.core.ui.component.EmptyState
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.theme.CardShape
import org.xmyun.starmate.core.ui.theme.PillShape
import org.xmyun.starmate.feature.memory.R
import org.xmyun.starmate.feature.memory.model.Mem

@Composable
fun MemoryScreen(
    onOpen: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: MemoryViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val query by viewModel.queryText.collectAsStateWithLifecycle()
    var editingDigest by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.memory_title),
        onBack = null,
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.memory_settings_title))
            }
        },
    ) { padding ->
        // 搜索框固定在标题栏下面；列表的底部留白交给 contentPadding，这样它能从悬浮标签栏下面滚过去。
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).imePadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = PillShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedBorderColor = Color.Transparent,
                ),
                placeholder = { Text(stringResource(R.string.memory_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.memory_search_clear))
                        }
                    }
                },
                singleLine = true,
            )
            if (groups.loaded && groups.isEmpty) {
                EmptyState(
                    icon = Icons.Outlined.Psychology,
                    title = stringResource(if (query.isEmpty()) R.string.memory_empty_title else R.string.memory_search_empty),
                    description = if (query.isEmpty()) stringResource(R.string.memory_empty_desc) else null,
                    modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "digest") {
                        SectionTitle(stringResource(R.string.memory_section_digest))
                        DigestCard(groups.digest, onEdit = { editingDigest = true })
                    }
                    section(R.string.memory_section_core, "core", groups.core, onOpen)
                    section(R.string.memory_section_between, "between", groups.betweenUs, onOpen)
                    section(R.string.memory_section_active, "active", groups.active, onOpen)
                    section(R.string.memory_section_insight, "insight", groups.insights, onOpen)
                    section(R.string.memory_section_archive, "archive", groups.archived, onOpen)
                }
            }
        }
    }

    if (editingDigest) {
        DigestDialog(
            initial = groups.digest,
            onSave = {
                viewModel.saveDigest(it)
                editingDigest = false
            },
            onDismiss = { editingDigest = false },
        )
    }
}

private fun LazyListScope.section(title: Int, key: String, list: List<Mem>, onOpen: (Long) -> Unit) {
    if (list.isEmpty()) return
    item(key = "title_$key") { SectionTitle("${stringResourceSafe(title)} · ${list.size}") }
    items(list, key = { "${key}_${it.id}" }) { memory -> MemoryRow(memory, onClick = { onOpen(memory.id) }) }
}

@Composable
private fun stringResourceSafe(id: Int): String = stringResource(id)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun DigestCard(digest: String, onEdit: () -> Unit) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(CardShape).clickable(onClick = onEdit),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = digest.ifBlank { stringResource(R.string.memory_digest_empty) },
                style = MaterialTheme.typography.bodyMedium,
                color = if (digest.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.memory_digest_edit_title))
        }
    }
}

@Composable
internal fun MemoryRow(memory: Mem, onClick: () -> Unit) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(CardShape).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (memory.pinned) {
                    Icon(
                        Icons.Outlined.PushPin,
                        contentDescription = stringResource(R.string.memory_status_pinned),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    text = memory.content,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = rowMeta(memory),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun rowMeta(memory: Mem): String {
    val parts = mutableListOf(categoryLabel(memory.category), stringResource(R.string.memory_importance_short, memory.importance))
    if (!memory.isValid) parts += stringResource(R.string.memory_status_invalid)
    return parts.joinToString(" · ")
}

@Composable
private fun DigestDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_digest_edit_title)) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.memory_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memory_cancel)) } },
    )
}
