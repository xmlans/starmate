package org.xmyun.starmate.feature.chat.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.core.model.ProviderKind
import org.xmyun.starmate.core.model.ProviderPreset
import org.xmyun.starmate.feature.chat.R
import org.xmyun.starmate.feature.chat.common.ErrorTexts

/** 服务商表单的输入项。引导的模型页和设置里的服务商编辑页共用。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderFormFields(
    presets: List<ProviderPreset>,
    form: ProviderForm,
    onSelectPreset: (String?) -> Unit,
    onChange: ((ProviderForm) -> ProviderForm) -> Unit,
    showPresets: Boolean,
    showModel: Boolean,
    models: List<String>,
    loadingModels: Boolean,
    onLoadModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showPresets) {
            Text(stringResource(R.string.chat_models_provider), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { preset ->
                    FilterChip(
                        selected = form.presetId == preset.id,
                        onClick = { onSelectPreset(preset.id) },
                        label = { Text(preset.displayName) },
                    )
                }
                FilterChip(
                    selected = form.presetId == null,
                    onClick = { onSelectPreset(null) },
                    label = { Text(stringResource(R.string.chat_models_custom)) },
                )
            }
        }
        if (form.presetId == null) {
            OutlinedTextField(
                value = form.displayName,
                onValueChange = { v -> onChange { it.copy(displayName = v) } },
                label = { Text(stringResource(R.string.chat_models_display_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = form.kind == ProviderKind.OPENAI_COMPAT,
                    onClick = { onChange { it.copy(kind = ProviderKind.OPENAI_COMPAT) } },
                    label = { Text(stringResource(R.string.chat_models_kind_openai)) },
                )
                FilterChip(
                    selected = form.kind == ProviderKind.ANTHROPIC,
                    onClick = { onChange { it.copy(kind = ProviderKind.ANTHROPIC) } },
                    label = { Text(stringResource(R.string.chat_models_kind_anthropic)) },
                )
            }
        }
        OutlinedTextField(
            value = form.baseUrl,
            onValueChange = { v -> onChange { it.copy(baseUrl = v) } },
            label = { Text(stringResource(R.string.chat_models_base_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.apiKey,
            onValueChange = { v -> onChange { it.copy(apiKey = v) } },
            label = { Text(stringResource(R.string.chat_models_api_key)) },
            placeholder = if (form.hasStoredKey) {
                { Text(stringResource(R.string.chat_models_key_kept)) }
            } else {
                null
            },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(if (keyVisible) R.string.chat_models_hide_key else R.string.chat_models_show_key),
                    )
                }
            },
            supportingText = { Text(stringResource(R.string.chat_models_key_note)) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (showModel) {
            ModelField(
                value = form.model,
                onValueChange = { v -> onChange { it.copy(model = v) } },
                models = models,
                loading = loadingModels,
                onLoad = onLoadModels,
            )
        }
    }
}

/** 模型名：可以手填，也可以从服务商拉列表选。 */
@Composable
fun ModelField(
    value: String,
    onValueChange: (String) -> Unit,
    models: List<String>,
    loading: Boolean,
    onLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
    Column(modifier) {
        Box {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(stringResource(R.string.chat_models_model)) },
                singleLine = true,
                trailingIcon = {
                    if (loading) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        IconButton(onClick = {
                            requested = true
                            if (models.isEmpty()) onLoad()
                            menuOpen = true
                        }) {
                            Icon(Icons.Outlined.ArrowDropDown, contentDescription = stringResource(R.string.chat_models_pick_model))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(
                expanded = menuOpen && models.isNotEmpty(),
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.heightIn(max = 320.dp),
            ) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = {
                            onValueChange(model)
                            menuOpen = false
                        },
                    )
                }
            }
        }
        if (requested && !loading && models.isEmpty()) {
            Text(
                stringResource(R.string.chat_models_no_list),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}

/** 测试按钮和结果。 */
@Composable
fun TestRow(state: TestState, onTest: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onTest, enabled = state != TestState.Running) {
            Text(stringResource(R.string.chat_models_test))
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) { TestStatus(state) }
    }
}

@Composable
fun TestStatus(state: TestState) {
    val colors = MaterialTheme.colorScheme
    when (state) {
        TestState.Idle -> Unit
        TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.chat_models_testing), style = MaterialTheme.typography.bodyMedium)
        }
        TestState.Ok -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.chat_models_test_ok), style = MaterialTheme.typography.bodyMedium, color = colors.primary)
        }
        is TestState.Failed -> FailureText(stringResource(ErrorTexts.message(state.kind)))
        is TestState.Invalid -> FailureText(stringResource(state.message))
    }
}

@Composable
private fun FailureText(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
