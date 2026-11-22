package org.xmyun.starmate.feature.mobileuse.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmyun.starmate.core.ui.component.EmptyState
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.feature.mobileuse.R
import org.xmyun.starmate.feature.mobileuse.guard.BlockCategory
import org.xmyun.starmate.feature.mobileuse.guard.Guard
import org.xmyun.starmate.feature.mobileuse.model.Finding
import org.xmyun.starmate.feature.mobileuse.model.FindingCode
import org.xmyun.starmate.feature.mobileuse.prefs.MobileUsePrefs
import org.xmyun.starmate.feature.mobileuse.service.AndroidPolicyProvider
import org.xmyun.starmate.feature.mobileuse.service.AppLabels

data class AllowlistRow(
    val packageName: String,
    val label: String,
    @StringRes val blockedReason: Int?,
    val checked: Boolean,
)

data class AllowlistUiState(val loading: Boolean = true, val rows: List<AllowlistRow> = emptyList())

@HiltViewModel
class AllowlistViewModel @Inject constructor(
    private val prefs: MobileUsePrefs,
    private val labels: AppLabels,
    private val policies: AndroidPolicyProvider,
    private val guard: Guard,
) : ViewModel() {

    private data class App(val packageName: String, val label: String, val reason: Int?)

    private val apps = MutableStateFlow<List<App>?>(null)

    val state: StateFlow<AllowlistUiState> = combine(apps, prefs.allowlist) { list, allowed ->
        if (list == null) {
            AllowlistUiState(loading = true)
        } else {
            AllowlistUiState(
                loading = false,
                // 被拦的应用即使以前存进了白名单，也显示为未勾选；守卫执行时同样不认
                rows = list.map { AllowlistRow(it.packageName, it.label, it.reason, it.reason == null && it.packageName in allowed) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AllowlistUiState())

    init {
        viewModelScope.launch {
            apps.value = withContext(Dispatchers.Default) {
                val policy = policies.current()
                labels.launchableApps().map { app ->
                    App(app.packageName, app.label, guard.allowlistBlockReason(app.packageName, app.label, policy)?.let(::reasonText))
                }
            }
        }
    }

    fun toggle(row: AllowlistRow, allowed: Boolean) {
        if (row.blockedReason != null) return
        viewModelScope.launch { prefs.setAllowed(row.packageName, allowed) }
    }

    @StringRes
    private fun reasonText(f: Finding): Int = when (f.code) {
        FindingCode.STARMATE_ITSELF -> R.string.mobileuse_block_self
        FindingCode.SYSTEM_UI, FindingCode.SYSTEM_APP -> R.string.mobileuse_block_system
        FindingCode.LAUNCHER -> R.string.mobileuse_block_launcher
        FindingCode.DEFAULT_SMS_APP -> R.string.mobileuse_block_sms
        FindingCode.NO_APP_NAME -> R.string.mobileuse_block_unknown
        else -> when (f.category) {
            BlockCategory.PAYMENT -> R.string.mobileuse_block_payment
            BlockCategory.BANK -> R.string.mobileuse_block_bank
            BlockCategory.BROKER -> R.string.mobileuse_block_broker
            BlockCategory.CRYPTO -> R.string.mobileuse_block_crypto
            BlockCategory.AUTHENTICATOR -> R.string.mobileuse_block_authenticator
            BlockCategory.PASSWORD_MANAGER -> R.string.mobileuse_block_password
            BlockCategory.SMS -> R.string.mobileuse_block_sms
            BlockCategory.SYSTEM -> R.string.mobileuse_block_system
            null -> R.string.mobileuse_block_unknown
        }
    }
}

/** 白名单页：已安装的应用，黑名单里的置灰不可选并注明原因。 */
@Composable
fun AllowlistScreen(onBack: () -> Unit, viewModel: AllowlistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScaffold(title = stringResource(R.string.mobileuse_allowlist_title), onBack = onBack) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.rows.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Apps,
                title = stringResource(R.string.mobileuse_allowlist_empty),
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(contentPadding = padding) {
                item {
                    Text(
                        text = stringResource(R.string.mobileuse_allowlist_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
                items(state.rows, key = { it.packageName }) { row ->
                    val enabled = row.blockedReason == null
                    ListItem(
                        headlineContent = { Text(row.label) },
                        supportingContent = {
                            Text(row.blockedReason?.let { stringResource(it) } ?: row.packageName)
                        },
                        trailingContent = { Checkbox(checked = row.checked, onCheckedChange = null, enabled = enabled) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .alpha(if (enabled) 1f else 0.45f)
                            .toggleable(
                                value = row.checked,
                                enabled = enabled,
                                role = Role.Checkbox,
                                onValueChange = { viewModel.toggle(row, it) },
                            ),
                    )
                }
            }
        }
    }
}
