package org.xmyun.starmate.feature.memory.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xmyun.starmate.core.ui.component.ConfirmDialog
import org.xmyun.starmate.core.ui.component.SettingsDivider
import org.xmyun.starmate.core.ui.component.SettingsRow
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.feature.memory.R

@Composable
fun MemorySettingsScreen(onBack: () -> Unit, viewModel: MemorySettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(title = stringResource(R.string.memory_settings_title), onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                state.message?.let { (res, arg) ->
                    Text(
                        text = if (arg == null) stringResource(res) else stringResource(res, arg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            item {
                SettingsSection(title = stringResource(R.string.memory_settings_search)) {
                    SettingsRow(
                        icon = Icons.Outlined.Hub,
                        title = stringResource(R.string.memory_settings_embedding),
                        summary = when (state.embeddingAvailable) {
                            true -> stringResource(R.string.memory_settings_embedding_on)
                            false -> stringResource(R.string.memory_settings_embedding_off)
                            null -> null
                        },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Outlined.Inventory2,
                        title = stringResource(R.string.memory_settings_reindex),
                        summary = stringResource(R.string.memory_settings_reindex_summary),
                        onClick = viewModel::rebuildVectors,
                        enabled = !state.busy && state.embeddingAvailable == true,
                    )
                }
            }
            item {
                SettingsSection(title = stringResource(R.string.memory_settings_organize)) {
                    SettingsRow(
                        icon = Icons.Outlined.Sync,
                        title = stringResource(R.string.memory_settings_consolidate),
                        summary = stringResource(R.string.memory_settings_consolidate_summary, state.pending),
                        onClick = viewModel::consolidateNow,
                        enabled = !state.busy,
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Outlined.AutoAwesome,
                        title = stringResource(R.string.memory_settings_reflect),
                        summary = stringResource(R.string.memory_settings_reflect_summary),
                        onClick = viewModel::reflectNow,
                        enabled = !state.busy,
                    )
                }
            }
            item {
                SettingsSection(title = null) {
                    SettingsRow(
                        icon = Icons.Outlined.DeleteForever,
                        title = stringResource(R.string.memory_settings_clear),
                        summary = stringResource(R.string.memory_settings_clear_summary),
                        onClick = { confirmClear = true },
                        enabled = !state.busy,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.memory_clear_confirm_title),
            text = stringResource(R.string.memory_clear_confirm_text),
            confirmLabel = stringResource(R.string.memory_clear_confirm),
            onConfirm = {
                confirmClear = false
                viewModel.clearAll()
            },
            onDismiss = { confirmClear = false },
            destructive = true,
            icon = Icons.Outlined.DeleteForever,
        )
    }
}
