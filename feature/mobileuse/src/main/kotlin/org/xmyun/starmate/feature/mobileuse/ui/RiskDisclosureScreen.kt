package org.xmyun.starmate.feature.mobileuse.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.feature.mobileuse.R
import org.xmyun.starmate.feature.mobileuse.prefs.MobileUsePrefs
import org.xmyun.starmate.feature.mobileuse.tool.TaskCoordinator

@HiltViewModel
class RiskViewModel @Inject constructor(
    private val prefs: MobileUsePrefs,
    private val coordinator: TaskCoordinator,
) : ViewModel() {
    val accepted: StateFlow<Boolean> =
        prefs.riskAccepted.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun accept(onDone: () -> Unit) {
        viewModelScope.launch {
            prefs.setRiskAccepted(true)
            prefs.setEnabled(true)
            onDone()
        }
    }

    fun revoke(onDone: () -> Unit) {
        coordinator.stopCurrent()
        viewModelScope.launch {
            prefs.setRiskAccepted(false)
            onDone()
        }
    }
}

private val SECTIONS: List<Pair<Int, Int>> = listOf(
    R.string.mobileuse_risk_what_title to R.string.mobileuse_risk_what_body,
    R.string.mobileuse_risk_sees_title to R.string.mobileuse_risk_sees_body,
    R.string.mobileuse_risk_mistakes_title to R.string.mobileuse_risk_mistakes_body,
    R.string.mobileuse_risk_never_title to R.string.mobileuse_risk_never_body,
    R.string.mobileuse_risk_stop_title to R.string.mobileuse_risk_stop_body,
    R.string.mobileuse_risk_log_title to R.string.mobileuse_risk_log_body,
)

/** 风险说明页：必须滚到底才能点“我已了解，开启”。 */
@Composable
fun RiskDisclosureScreen(onBack: () -> Unit, viewModel: RiskViewModel = hiltViewModel()) {
    val accepted by viewModel.accepted.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val reachedEnd by remember { derivedStateOf { scroll.maxValue == 0 || scroll.value >= scroll.maxValue - 4 } }

    SettingsScaffold(title = stringResource(R.string.mobileuse_risk_title), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.mobileuse_risk_lead),
                style = MaterialTheme.typography.bodyLarge,
            )
            SECTIONS.forEach { (title, body) -> RiskSection(title, body) }
            Spacer(Modifier.height(24.dp))
            if (accepted) {
                OutlinedButton(onClick = { viewModel.revoke(onBack) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.mobileuse_risk_revoke))
                }
            } else {
                Button(
                    onClick = { viewModel.accept(onBack) },
                    enabled = reachedEnd,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.mobileuse_risk_accept))
                }
                if (!reachedEnd) {
                    Text(
                        text = stringResource(R.string.mobileuse_risk_scroll_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RiskSection(@StringRes title: Int, @StringRes body: Int) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(6.dp))
    Text(text = stringResource(body), style = MaterialTheme.typography.bodyLarge)
}
