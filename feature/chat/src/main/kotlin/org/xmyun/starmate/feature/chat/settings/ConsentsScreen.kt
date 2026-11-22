package org.xmyun.starmate.feature.chat.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.model.ToolConsentStore
import org.xmyun.starmate.core.ui.component.EmptyState
import org.xmyun.starmate.core.ui.component.SettingsDivider
import org.xmyun.starmate.core.ui.component.SettingsRow
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.feature.chat.R

@HiltViewModel
class ConsentsViewModel @Inject constructor(private val consent: ToolConsentStore) : ViewModel() {
    val remembered: StateFlow<List<String>> = consent.remembered.map { it.sorted() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun forget(toolName: String) {
        viewModelScope.launch { consent.forget(toolName) }
    }
}

/** 已允许的操作：用户勾选过“以后不再问”的工具，可以逐个撤销。 */
@Composable
fun ConsentsScreen(viewModel: ConsentsViewModel, onBack: () -> Unit) {
    val tools by viewModel.remembered.collectAsState()
    SettingsScaffold(title = stringResource(R.string.chat_consents_title), onBack = onBack) { padding ->
        if (tools.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.VerifiedUser,
                title = stringResource(R.string.chat_consents_empty),
                description = stringResource(R.string.chat_consents_empty_body),
                modifier = Modifier.padding(padding),
            )
            return@SettingsScaffold
        }
        LazyColumn(contentPadding = padding) {
            item {
                SettingsSection(title = stringResource(R.string.chat_consents_section)) {
                    tools.forEachIndexed { index, tool ->
                        if (index > 0) SettingsDivider()
                        SettingsRow(
                            icon = Icons.Outlined.Build,
                            title = tool,
                            trailing = {
                                TextButton(onClick = { viewModel.forget(tool) }) {
                                    Text(stringResource(R.string.chat_consents_revoke))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
