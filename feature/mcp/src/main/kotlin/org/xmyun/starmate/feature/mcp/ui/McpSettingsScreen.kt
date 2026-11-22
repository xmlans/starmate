package org.xmyun.starmate.feature.mcp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.core.ui.component.SettingsSwitchRow
import org.xmyun.starmate.feature.mcp.FailureKind
import org.xmyun.starmate.feature.mcp.McpPreset
import org.xmyun.starmate.feature.mcp.R
import org.xmyun.starmate.feature.mcp.ServerStatus

@Composable
fun McpSettingsScreen(
    onBack: () -> Unit,
    viewModel: McpSettingsViewModel = hiltViewModel(),
) {
    SecureWindow()
    val presets by viewModel.presets.collectAsStateWithLifecycle()

    SettingsScaffold(title = stringResource(R.string.mcp_settings_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.mcp_settings_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            presets.forEach { ui -> PresetCard(ui, viewModel) }
        }
    }
}

@Composable
private fun PresetCard(ui: PresetUi, viewModel: McpSettingsViewModel) {
    val preset = ui.preset
    var keyInput by remember(preset) { mutableStateOf("") }
    SettingsSection(title = stringResource(preset.titleRes)) {
        SettingsSwitchRow(
            icon = if (preset == McpPreset.TAVILY) Icons.Outlined.TravelExplore else Icons.Outlined.Map,
            title = stringResource(R.string.mcp_enable),
            summary = stringResource(preset.descriptionRes),
            checked = ui.enabled,
            onCheckedChange = { viewModel.setEnabled(preset, it) },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it.trim().take(200) },
                label = { Text(stringResource(R.string.mcp_key_label)) },
                placeholder = { if (ui.hasKey) Text(stringResource(R.string.mcp_key_saved)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    viewModel.saveKey(preset, keyInput)
                    keyInput = ""
                },
                enabled = keyInput.isNotBlank() && !ui.busy,
            ) { Text(stringResource(R.string.mcp_key_save)) }
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { viewModel.test(preset) }, enabled = ui.hasKey && !ui.busy) {
                Text(stringResource(R.string.mcp_test))
            }
            if (ui.hasKey) {
                TextButton(onClick = { viewModel.clearKey(preset) }, enabled = !ui.busy) {
                    Text(stringResource(R.string.mcp_key_clear))
                }
            }
            if (ui.busy) CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
        }
        Text(
            text = statusText(ui),
            style = MaterialTheme.typography.bodyMedium,
            color = if (ui.status is ServerStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (ui.toolNames.isNotEmpty()) {
            Text(
                text = stringResource(R.string.mcp_tools_found, ui.toolNames.joinToString("、")),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun statusText(ui: PresetUi): String = when (val s = ui.status) {
    ServerStatus.NotConfigured -> stringResource(R.string.mcp_status_not_configured)
    ServerStatus.Unchecked -> stringResource(R.string.mcp_status_unchecked)
    ServerStatus.Ok -> stringResource(R.string.mcp_status_ok, ui.toolNames.size)
    is ServerStatus.Failed -> stringResource(
        R.string.mcp_status_failed,
        when (s.kind) {
            FailureKind.NOT_CONFIGURED -> stringResource(R.string.mcp_status_not_configured)
            FailureKind.UNAUTHORIZED -> stringResource(R.string.mcp_reason_unauthorized)
            FailureKind.HTTP -> stringResource(R.string.mcp_reason_http, s.httpCode ?: 0)
            FailureKind.UNREACHABLE -> stringResource(R.string.mcp_reason_unreachable)
            FailureKind.TIMEOUT -> stringResource(R.string.mcp_reason_timeout)
            FailureKind.PROTOCOL -> stringResource(R.string.mcp_reason_protocol)
        },
    )
}

/** 输入 key 的页面禁止截屏和录屏，离开时恢复。 */
@Composable
private fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
