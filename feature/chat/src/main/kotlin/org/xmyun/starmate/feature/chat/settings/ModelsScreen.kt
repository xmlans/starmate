package org.xmyun.starmate.feature.chat.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ProviderConfig
import org.xmyun.starmate.core.model.RoleAssignment
import org.xmyun.starmate.core.ui.component.SettingsChevron
import org.xmyun.starmate.core.ui.component.SettingsDivider
import org.xmyun.starmate.core.ui.component.SettingsRow
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.feature.chat.R

/** 模型配置：历史上限、服务商列表、各模型角色的分配。 */
@Composable
fun ModelsScreen(
    viewModel: ModelsViewModel,
    onBack: () -> Unit,
    onEditProvider: (String?) -> Unit,
) {
    val providers by viewModel.providers.collectAsState()
    val assignments by viewModel.assignments.collectAsState()
    val budget by viewModel.historyBudget.collectAsState()
    val roleForm by viewModel.roleForm.collectAsState()

    SettingsScaffold(title = stringResource(R.string.chat_models_title), onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                SettingsSection(title = stringResource(R.string.chat_models_section_roles)) {
                    ModelRole.entries.forEachIndexed { index, role ->
                        if (index > 0) SettingsDivider()
                        RoleRow(
                            role = role,
                            assignment = assignments[role],
                            providers = providers,
                            enabled = providers.isNotEmpty(),
                            onClick = { viewModel.openRole(role) },
                        )
                    }
                }
                val chat = assignments[ModelRole.CHAT]
                if (chat != null && !chat.supportsTools) {
                    ToolsHint()
                }
            }
            item {
                SettingsSection(title = stringResource(R.string.chat_models_section_providers)) {
                    providers.forEachIndexed { index, provider ->
                        if (index > 0) SettingsDivider()
                        SettingsRow(
                            icon = Icons.Outlined.Cloud,
                            title = provider.displayName,
                            summary = provider.baseUrl,
                            onClick = { onEditProvider(provider.id) },
                            trailing = { SettingsChevron() },
                        )
                    }
                    if (providers.isNotEmpty()) SettingsDivider()
                    SettingsRow(
                        icon = Icons.Outlined.Add,
                        title = stringResource(R.string.chat_models_add_provider),
                        onClick = { onEditProvider(null) },
                    )
                }
            }
            item {
                HistoryBudgetSection(budget = budget, onChange = viewModel::setHistoryBudget)
            }
        }
    }

    roleForm?.let { form ->
        RoleDialog(
            form = form,
            providers = providers,
            existing = assignments[form.role],
            onChange = viewModel::updateRole,
            onLoadModels = viewModel::loadRoleModels,
            onTest = viewModel::testRole,
            onSave = viewModel::saveRole,
            onClear = { viewModel.clearRole(form.role) },
            onDismiss = viewModel::closeRole,
        )
    }
}

@Composable
private fun RoleRow(
    role: ModelRole,
    assignment: RoleAssignment?,
    providers: List<ProviderConfig>,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val summary = if (assignment != null) {
        val provider = providers.firstOrNull { it.id == assignment.providerId }?.displayName ?: assignment.providerId
        "$provider · ${assignment.model}"
    } else {
        stringResource(
            when (role) {
                ModelRole.CHAT -> R.string.chat_models_role_unset_required
                ModelRole.BACKGROUND, ModelRole.VISION -> R.string.chat_models_role_follow_chat
                else -> R.string.chat_models_role_unset
            },
        )
    }
    SettingsRow(
        icon = roleIcon(role),
        title = stringResource(roleTitle(role)),
        summary = summary,
        onClick = onClick,
        enabled = enabled,
        trailing = { SettingsChevron() },
    )
}

@Composable
private fun ToolsHint() {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.chat_models_no_tools),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryBudgetSection(budget: Int, onChange: (Int) -> Unit) {
    var dragging by remember(budget) { mutableFloatStateOf(budget.toFloat()) }
    SettingsSection(title = stringResource(R.string.chat_models_section_history)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                stringResource(R.string.chat_models_history_value, dragging.roundToInt()),
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = dragging,
                onValueChange = { dragging = (it / STEP).roundToInt() * STEP },
                onValueChangeFinished = { onChange(dragging.roundToInt()) },
                valueRange = ModelsViewModel.MIN_HISTORY_BUDGET.toFloat()..ModelsViewModel.MAX_HISTORY_BUDGET.toFloat(),
            )
            Text(
                stringResource(R.string.chat_models_history_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val STEP = 1000f

@Composable
private fun RoleDialog(
    form: RoleForm,
    providers: List<ProviderConfig>,
    existing: RoleAssignment?,
    onChange: ((RoleForm) -> RoleForm) -> Unit,
    onLoadModels: () -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 20.dp),
        title = { Text(stringResource(roleTitle(form.role))) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(roleDescription(form.role)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.chat_models_provider), style = MaterialTheme.typography.labelLarge)
                ProviderChips(providers = providers, selected = form.providerId) { id ->
                    onChange { it.copy(providerId = id, models = emptyList()) }
                }
                ModelField(
                    value = form.model,
                    onValueChange = { v -> onChange { it.copy(model = v) } },
                    models = form.models,
                    loading = form.loadingModels,
                    onLoad = onLoadModels,
                )
                if (form.role == ModelRole.VISION) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.chat_models_supports_images), modifier = Modifier.weight(1f))
                        Switch(checked = form.supportsImages, onCheckedChange = { v -> onChange { it.copy(supportsImages = v) } })
                    }
                }
                if (form.role == ModelRole.TTS) {
                    OutlinedTextField(
                        value = form.voice,
                        onValueChange = { v -> onChange { it.copy(voice = v) } },
                        label = { Text(stringResource(R.string.chat_models_tts_voice)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = form.format,
                        onValueChange = { v -> onChange { it.copy(format = v) } },
                        label = { Text(stringResource(R.string.chat_models_tts_format)) },
                        placeholder = { Text("mp3") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = form.instructions,
                        onValueChange = { v -> onChange { it.copy(instructions = v) } },
                        label = { Text(stringResource(R.string.chat_models_tts_instructions)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (existing != null && !existing.supportsTools && form.role in RoleForm.CHAT_ROLES) {
                    Text(
                        stringResource(R.string.chat_models_no_tools),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (form.testable) {
                    TestRow(state = form.test, onTest = onTest)
                } else if (form.test is TestState.Invalid) {
                    TestStatus(form.test)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text(stringResource(R.string.chat_save)) }
        },
        dismissButton = {
            Row {
                if (existing != null && form.role != ModelRole.CHAT) {
                    TextButton(onClick = onClear) { Text(stringResource(R.string.chat_models_clear_role)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(org.xmyun.starmate.core.ui.R.string.core_cancel)) }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderChips(providers: List<ProviderConfig>, selected: String?, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        providers.forEach { provider ->
            FilterChip(
                selected = provider.id == selected,
                onClick = { onSelect(provider.id) },
                label = { Text(provider.displayName) },
            )
        }
    }
}

fun roleTitle(role: ModelRole): Int = when (role) {
    ModelRole.CHAT -> R.string.chat_models_role_chat
    ModelRole.BACKGROUND -> R.string.chat_models_role_background
    ModelRole.VISION -> R.string.chat_models_role_vision
    ModelRole.EMBEDDING -> R.string.chat_models_role_embedding
    ModelRole.TTS -> R.string.chat_models_role_tts
    ModelRole.STT -> R.string.chat_models_role_stt
}

private fun roleDescription(role: ModelRole): Int = when (role) {
    ModelRole.CHAT -> R.string.chat_models_role_chat_desc
    ModelRole.BACKGROUND -> R.string.chat_models_role_background_desc
    ModelRole.VISION -> R.string.chat_models_role_vision_desc
    ModelRole.EMBEDDING -> R.string.chat_models_role_embedding_desc
    ModelRole.TTS -> R.string.chat_models_role_tts_desc
    ModelRole.STT -> R.string.chat_models_role_stt_desc
}

private fun roleIcon(role: ModelRole): ImageVector = when (role) {
    ModelRole.CHAT -> Icons.AutoMirrored.Outlined.Chat
    ModelRole.BACKGROUND -> Icons.Outlined.AutoAwesome
    ModelRole.VISION -> Icons.Outlined.Image
    ModelRole.EMBEDDING -> Icons.Outlined.Hub
    ModelRole.TTS -> Icons.Outlined.GraphicEq
    ModelRole.STT -> Icons.Outlined.Hearing
}
