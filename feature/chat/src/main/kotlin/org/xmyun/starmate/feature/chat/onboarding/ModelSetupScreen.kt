package org.xmyun.starmate.feature.chat.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.xmyun.starmate.feature.chat.R
import org.xmyun.starmate.feature.chat.common.SecureWindow
import org.xmyun.starmate.feature.chat.settings.ModelsViewModel
import org.xmyun.starmate.feature.chat.settings.ProviderFormFields
import org.xmyun.starmate.feature.chat.settings.TestRow

/** 第 3 步：配置主聊天模型。测试连接通过才能继续。这一页禁止截屏。 */
@Composable
fun ModelSetupScreen(
    viewModel: ModelsViewModel,
    onBack: (() -> Unit)?,
    onDone: () -> Unit,
) {
    SecureWindow()
    val form by viewModel.form.collectAsState()
    val test by viewModel.formTest.collectAsState()
    val canContinue by viewModel.canContinue.collectAsState()
    val models by viewModel.formModels.collectAsState()
    val loading by viewModel.loadingModels.collectAsState()
    val scope = rememberCoroutineScope()

    OnboardingPage(
        step = 2,
        title = stringResource(R.string.chat_model_setup_title),
        description = stringResource(R.string.chat_model_setup_body),
        onBack = onBack,
        bottom = {
            Button(
                onClick = { scope.launch { if (viewModel.completeSetup()) onDone() } },
                enabled = canContinue,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.chat_next)) }
        },
    ) {
        ProviderFormFields(
            presets = viewModel.presets,
            form = form,
            onSelectPreset = viewModel::selectPreset,
            onChange = viewModel::updateForm,
            showPresets = true,
            showModel = true,
            models = models,
            loadingModels = loading,
            onLoadModels = viewModel::loadFormModels,
        )
        Spacer(Modifier.height(16.dp))
        TestRow(state = test, onTest = viewModel::testConnection)
    }
}
