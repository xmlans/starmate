package org.xmyun.starmate.feature.tools.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.feature.tools.R

/** Android 13+ 侧载应用要先在应用信息页允许受限设置，才能开通知使用权。 */
@Composable
fun RestrictedSettingsGuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val steps = listOf(
        R.string.tools_restricted_step1,
        R.string.tools_restricted_step2,
        R.string.tools_restricted_step3,
        R.string.tools_restricted_step4,
    )
    SettingsScaffold(title = stringResource(R.string.tools_restricted_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.tools_restricted_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            SettingsSection(title = stringResource(R.string.tools_restricted_steps)) {
                steps.forEachIndexed { index, step ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Text(text = stringResource(step), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Row(Modifier.padding(16.dp)) {
                Button(onClick = { context.safeStart(appDetailsIntent(context)) }) {
                    Text(stringResource(R.string.tools_restricted_open))
                }
                OutlinedButton(
                    onClick = { context.safeStart(context.notificationListenerSettingsIntent().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(stringResource(R.string.tools_restricted_open_listener))
                }
            }
        }
    }
}
