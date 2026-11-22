package org.xmyun.starmate.feature.chat.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.ui.component.SettingsDivider
import org.xmyun.starmate.core.ui.component.SettingsRow
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.core.ui.theme.ThemeAccent
import org.xmyun.starmate.core.ui.theme.ThemePrefs
import org.xmyun.starmate.core.ui.theme.starmateColorScheme
import org.xmyun.starmate.feature.chat.R

@HiltViewModel
class AppearanceViewModel @Inject constructor(private val themePrefs: ThemePrefs) : ViewModel() {
    val accent: StateFlow<ThemeAccent> = themePrefs.accent.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeAccent.Default)

    fun select(accent: ThemeAccent) {
        viewModelScope.launch { themePrefs.setAccent(accent) }
    }
}

/** 外观：主题色。 */
@Composable
fun AppearanceScreen(viewModel: AppearanceViewModel, onBack: () -> Unit) {
    val current by viewModel.accent.collectAsState()
    val dark = isSystemInDarkTheme()
    SettingsScaffold(title = stringResource(R.string.chat_appearance_title), onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                SettingsSection(title = stringResource(R.string.chat_appearance_accent)) {
                    ThemeAccent.entries.forEachIndexed { index, accent ->
                        if (index > 0) SettingsDivider()
                        val selected = accent == current
                        val scheme = remember(accent, dark) { starmateColorScheme(accent, dark) }
                        SettingsRow(
                            icon = null,
                            title = stringResource(accent.label),
                            modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = { viewModel.select(accent) }),
                            trailing = {
                                Box(
                                    Modifier.size(28.dp).clip(CircleShape).background(scheme.primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) {
                                        Icon(Icons.Outlined.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
