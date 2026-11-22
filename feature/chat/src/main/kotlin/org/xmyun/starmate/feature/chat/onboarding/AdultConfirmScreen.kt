package org.xmyun.starmate.feature.chat.onboarding

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.feature.chat.R

/** 第 2 步：成年确认。不勾选不能继续。 */
@Composable
fun AdultConfirmScreen(
    viewModel: OnboardingViewModel,
    onBack: () -> Unit,
    onNext: (String?) -> Unit,
) {
    var checked by rememberSaveable { mutableStateOf(false) }
    OnboardingPage(
        step = 1,
        title = stringResource(R.string.chat_adult_title),
        description = stringResource(R.string.chat_adult_body),
        onBack = onBack,
        bottom = {
            Button(
                onClick = { viewModel.confirmAdult(onNext) },
                enabled = checked,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.chat_next)) }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { checked = it })
                .padding(vertical = 8.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.chat_adult_check), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
