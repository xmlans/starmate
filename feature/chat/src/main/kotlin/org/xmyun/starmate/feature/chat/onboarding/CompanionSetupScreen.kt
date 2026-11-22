package org.xmyun.starmate.feature.chat.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.core.ui.component.Avatar
import org.xmyun.starmate.feature.chat.R

/** 第 4 步：给她起名、选头像。都有默认值，可以跳过。完成后开始初识访谈。 */
@Composable
fun CompanionSetupScreen(
    viewModel: OnboardingViewModel,
    onBack: (() -> Unit)?,
    onDone: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.loadCompanion() }
    val name by viewModel.name.collectAsState()
    val avatar by viewModel.avatar.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.pickAvatar(uri)
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    OnboardingPage(
        step = 3,
        title = stringResource(R.string.chat_companion_setup_title),
        description = stringResource(R.string.chat_companion_setup_body),
        onBack = onBack,
        bottom = {
            Button(onClick = { viewModel.finishCompanion(save = true, onNext = onDone) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chat_companion_setup_start))
            }
            TextButton(onClick = { viewModel.finishCompanion(save = false, onNext = onDone) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chat_skip))
            }
        },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Avatar(
                uri = avatar,
                name = name,
                size = 96.dp,
                onClick = pick,
                contentDescription = stringResource(R.string.chat_companion_avatar),
            )
            TextButton(onClick = pick) { Text(stringResource(R.string.chat_companion_pick_avatar)) }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = viewModel::onNameChange,
            label = { Text(stringResource(R.string.chat_companion_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.chat_companion_setup_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
