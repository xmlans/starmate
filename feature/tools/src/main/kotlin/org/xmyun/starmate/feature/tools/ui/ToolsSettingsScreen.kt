package org.xmyun.starmate.feature.tools.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xmyun.starmate.core.ui.component.SettingsChevron
import org.xmyun.starmate.core.ui.component.SettingsRow
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.feature.tools.R
import org.xmyun.starmate.feature.tools.common.GroupStatus
import org.xmyun.starmate.feature.tools.common.ToolGroup

internal val ToolGroup.icon: ImageVector
    get() = when (this) {
        ToolGroup.CALENDAR -> Icons.Outlined.CalendarMonth
        ToolGroup.ALARM -> Icons.Outlined.Alarm
        ToolGroup.CONTACTS -> Icons.Outlined.Contacts
        ToolGroup.LOCATION -> Icons.Outlined.WbSunny
        ToolGroup.HEALTH -> Icons.Outlined.FavoriteBorder
        ToolGroup.MUSIC -> Icons.Outlined.LibraryMusic
    }

@get:StringRes
internal val ToolGroup.titleRes: Int
    get() = when (this) {
        ToolGroup.CALENDAR -> R.string.tools_group_calendar
        ToolGroup.ALARM -> R.string.tools_group_alarm
        ToolGroup.CONTACTS -> R.string.tools_group_contacts
        ToolGroup.LOCATION -> R.string.tools_group_location
        ToolGroup.HEALTH -> R.string.tools_group_health
        ToolGroup.MUSIC -> R.string.tools_group_music
    }

@get:StringRes
internal val ToolGroup.seesRes: Int
    get() = when (this) {
        ToolGroup.CALENDAR -> R.string.tools_group_calendar_sees
        ToolGroup.ALARM -> R.string.tools_group_alarm_sees
        ToolGroup.CONTACTS -> R.string.tools_group_contacts_sees
        ToolGroup.LOCATION -> R.string.tools_group_location_sees
        ToolGroup.HEALTH -> R.string.tools_group_health_sees
        ToolGroup.MUSIC -> R.string.tools_group_music_sees
    }

@get:StringRes
internal val GroupStatus.labelRes: Int
    get() = when (this) {
        GroupStatus.OFF -> R.string.tools_status_off
        GroupStatus.MISSING_PERMISSION -> R.string.tools_status_missing
        GroupStatus.ON_DEMAND -> R.string.tools_status_on_demand
        GroupStatus.AMBIENT -> R.string.tools_status_ambient
    }

@Composable
fun ToolsSettingsScreen(
    onBack: () -> Unit,
    onOpenGroup: (ToolGroup) -> Unit,
    viewModel: ToolsSettingsViewModel = hiltViewModel(),
) {
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    SettingsScaffold(title = stringResource(R.string.tools_settings_title), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.tools_settings_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            SettingsSection(title = null) {
                ToolGroup.entries.forEach { group ->
                    val status = statuses[group]
                    SettingsRow(
                        icon = group.icon,
                        title = stringResource(group.titleRes),
                        summary = status?.let { stringResource(it.labelRes) },
                        onClick = { onOpenGroup(group) },
                        trailing = { SettingsChevron() },
                    )
                }
            }
        }
    }
}
