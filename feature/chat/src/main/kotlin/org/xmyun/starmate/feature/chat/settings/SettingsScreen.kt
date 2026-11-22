package org.xmyun.starmate.feature.chat.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import org.xmyun.starmate.core.ui.component.SettingsChevron
import org.xmyun.starmate.core.ui.component.SettingsDivider
import org.xmyun.starmate.core.ui.component.SettingsRow
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.core.ui.contract.SettingsGroup
import org.xmyun.starmate.core.ui.contract.SettingsItem
import org.xmyun.starmate.feature.chat.R

@HiltViewModel
class SettingsViewModel @Inject constructor(
    entries: Set<@JvmSuppressWildcards FeatureEntry>,
) : ViewModel() {
    val groups: List<Pair<SettingsGroup, List<SettingsItem>>> = groupSettings(entries.flatMap { it.settingsItems })

    companion object {
        /** 按 SettingsGroup 的声明顺序分组，组内按 order、再按路由排；空组不出现。 */
        fun groupSettings(items: List<SettingsItem>): List<Pair<SettingsGroup, List<SettingsItem>>> =
            SettingsGroup.entries.mapNotNull { group ->
                items.filter { it.group == group }
                    .distinctBy { it.route }
                    .sortedWith(compareBy({ it.order }, { it.route }))
                    .takeIf { it.isNotEmpty() }
                    ?.let { group to it }
            }
    }
}

/** 设置首页（底部标签）：各模块的设置项按分组显示。 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onOpen: (String) -> Unit) {
    SettingsScaffold(title = stringResource(R.string.chat_settings_title), onBack = null) { padding ->
        LazyColumn(contentPadding = padding) {
            items(viewModel.groups, key = { it.first.name }) { (group, items) ->
                SettingsSection(title = stringResource(groupTitle(group))) {
                    items.forEachIndexed { index, item ->
                        if (index > 0) SettingsDivider()
                        SettingsRow(
                            icon = item.icon,
                            title = stringResource(item.title),
                            onClick = { onOpen(item.route) },
                            trailing = { SettingsChevron() },
                        )
                    }
                }
            }
        }
    }
}

private fun groupTitle(group: SettingsGroup): Int = when (group) {
    SettingsGroup.COMPANION -> R.string.chat_settings_group_companion
    SettingsGroup.MODELS -> R.string.chat_settings_group_models
    SettingsGroup.ABILITIES -> R.string.chat_settings_group_abilities
    SettingsGroup.DATA -> R.string.chat_settings_group_data
    SettingsGroup.LAB -> R.string.chat_settings_group_lab
}
