package org.xmyun.starmate.feature.chat.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.core.ui.component.ConfirmDialog
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.feature.chat.R
import org.xmyun.starmate.feature.chat.common.SecureWindow

/** 新建或编辑一个服务商。这一页要输入 key，禁止截屏。 */
@Composable
fun ProviderEditorScreen(viewModel: ModelsViewModel, onBack: () -> Unit) {
    SecureWindow()
    val form by viewModel.form.collectAsState()
    val test by viewModel.formTest.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val isNew = !viewModel.editingExisting

    SettingsScaffold(
        title = stringResource(if (isNew) R.string.chat_models_add_provider else R.string.chat_models_edit_provider),
        onBack = onBack,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProviderFormFields(
                presets = viewModel.presets,
                form = form,
                onSelectPreset = viewModel::selectPreset,
                onChange = viewModel::updateForm,
                showPresets = isNew,
                showModel = false,
                models = emptyList(),
                loadingModels = false,
                onLoadModels = {},
            )
            if (test is TestState.Invalid) TestStatus(test)
            Text(
                stringResource(R.string.chat_models_provider_test_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { viewModel.saveProvider(onBack) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chat_save))
            }
            if (!isNew) {
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.chat_models_delete_provider)) }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.chat_models_delete_provider),
            text = stringResource(R.string.chat_models_delete_provider_text),
            confirmLabel = stringResource(R.string.chat_action_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                form.providerId?.let { viewModel.deleteProvider(it, onBack) }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
