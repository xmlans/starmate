package org.xmyun.starmate.feature.chat.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.core.ui.theme.CardShape
import org.xmyun.starmate.feature.chat.R

/** 第 1 步：欢迎，说明 BYOK；另有“从备份恢复”。 */
@Composable
fun WelcomeScreen(
    viewModel: OnboardingViewModel,
    onStart: () -> Unit,
    onRestored: (String?) -> Unit,
) {
    val restoring by viewModel.restoring.collectAsState()
    val error by viewModel.restoreError.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.restore(uri, onRestored)
    }

    OnboardingPage(
        step = null,
        title = stringResource(R.string.chat_welcome_title),
        description = stringResource(R.string.chat_welcome_body),
        onBack = null,
        wallpaper = true,
        bottom = {
            Button(onClick = onStart, enabled = !restoring, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chat_welcome_start))
            }
            TextButton(
                onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
                enabled = !restoring,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (restoring) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.chat_welcome_restore))
            }
        },
    ) {
        Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Point(Icons.Outlined.Key, stringResource(R.string.chat_welcome_point_key))
                Point(Icons.Outlined.PhoneAndroid, stringResource(R.string.chat_welcome_point_local))
                Point(Icons.Outlined.Lock, stringResource(R.string.chat_welcome_point_network))
            }
        }
        error?.let {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
