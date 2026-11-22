package org.xmyun.starmate.feature.tools.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xmyun.starmate.core.ui.component.SettingsChevron
import org.xmyun.starmate.core.ui.component.SettingsRow
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.core.ui.component.SettingsSwitchRow
import org.xmyun.starmate.feature.tools.R
import org.xmyun.starmate.feature.tools.common.ToolGroup
import org.xmyun.starmate.feature.tools.health.HealthAvailability
import org.xmyun.starmate.feature.tools.health.HealthConnectGateway

@Composable
fun GroupDetailScreen(
    onBack: () -> Unit,
    onOpenRestrictedGuide: () -> Unit,
    viewModel: GroupDetailViewModel = hiltViewModel(),
) {
    val group = viewModel.group
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val requestPermission = rememberGroupPermissionRequester(
        group = group,
        healthConnectAvailable = state.health == HealthAvailability.AVAILABLE,
        onDone = { viewModel.refresh() },
    )

    SettingsScaffold(title = stringResource(group.titleRes), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(title = stringResource(R.string.tools_detail_what_she_sees)) {
                Text(
                    text = stringResource(group.seesRes),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            SettingsSection(title = null) {
                SettingsSwitchRow(
                    icon = group.icon,
                    title = stringResource(R.string.tools_detail_enable),
                    checked = state.enabled,
                    onCheckedChange = { on ->
                        viewModel.setEnabled(on)
                        if (on && !state.granted && group != ToolGroup.ALARM) requestPermission()
                    },
                )
            }

            if (group.hasAmbient) {
                SettingsSection(title = stringResource(R.string.tools_detail_mode)) {
                    Column(Modifier.selectableGroup()) {
                        ModeRow(
                            title = stringResource(R.string.tools_mode_on_demand),
                            summary = stringResource(R.string.tools_mode_on_demand_desc),
                            selected = !state.ambient,
                            enabled = state.enabled,
                            onSelect = { viewModel.setAmbient(false) },
                        )
                        ModeRow(
                            title = stringResource(R.string.tools_mode_ambient),
                            summary = stringResource(R.string.tools_mode_ambient_desc),
                            selected = state.ambient,
                            enabled = state.enabled,
                            onSelect = { viewModel.setAmbient(true) },
                        )
                    }
                }
            }

            if (group != ToolGroup.ALARM) {
                SettingsSection(title = stringResource(R.string.tools_detail_permission)) {
                    SettingsRow(
                        icon = Icons.Outlined.Key,
                        title = stringResource(if (state.granted) R.string.tools_permission_granted else R.string.tools_permission_missing),
                        summary = if (group == ToolGroup.LOCATION) stringResource(R.string.tools_location_permission_note) else null,
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!state.granted) {
                            Button(onClick = requestPermission) { Text(stringResource(R.string.tools_grant)) }
                        }
                        OutlinedButton(onClick = { context.safeStart(appDetailsIntent(context)) }) {
                            Text(stringResource(R.string.tools_open_app_settings))
                        }
                    }
                    if (group == ToolGroup.MUSIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        SettingsRow(
                            icon = Icons.Outlined.Info,
                            title = stringResource(R.string.tools_restricted_title),
                            summary = stringResource(R.string.tools_music_restricted_hint),
                            onClick = onOpenRestrictedGuide,
                            trailing = { SettingsChevron() },
                        )
                    }
                }
            }

            if (group == ToolGroup.HEALTH) HealthConnectSection(state.health, context)
            if (group == ToolGroup.LOCATION) CitySection(state, viewModel)
        }
    }
}

@Composable
private fun ModeRow(title: String, summary: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
    )
}

@Composable
private fun HealthConnectSection(availability: HealthAvailability?, context: Context) {
    SettingsSection(title = stringResource(R.string.tools_hc_title)) {
        val text = when (availability) {
            HealthAvailability.AVAILABLE -> R.string.tools_hc_available
            HealthAvailability.NEEDS_UPDATE -> R.string.tools_hc_needs_update
            HealthAvailability.UNAVAILABLE, null -> R.string.tools_hc_unavailable
        }
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            if (availability == HealthAvailability.AVAILABLE) {
                TextButton(onClick = {
                    context.safeStart(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.tools_hc_open)) }
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || availability == HealthAvailability.NEEDS_UPDATE) {
                // Android 14+ 系统自带 Health Connect，只有低版本或需要更新时才去应用商店
                TextButton(onClick = {
                    context.safeStart(HealthConnectGateway.installIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.tools_hc_install)) }
            }
        }
    }
}

@Composable
private fun CitySection(state: GroupDetailState, viewModel: GroupDetailViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    SettingsSection(title = stringResource(R.string.tools_city_title)) {
        Text(
            text = stringResource(R.string.tools_city_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        state.manualCity?.let { city ->
            SettingsRow(
                icon = Icons.Outlined.LocationCity,
                title = city,
                trailing = { TextButton(onClick = viewModel::clearCity) { Text(stringResource(R.string.tools_city_clear)) } },
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(80) },
                label = { Text(stringResource(R.string.tools_city_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.saveCity(input) }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { viewModel.saveCity(input) }, enabled = !state.cityBusy && input.isNotBlank()) {
                Text(stringResource(R.string.tools_city_save))
            }
        }
        state.cityMessage?.let { message ->
            val text = when (message) {
                CityMessage.SAVED -> stringResource(R.string.tools_city_saved, state.manualCity.orEmpty())
                CityMessage.NOT_FOUND -> stringResource(R.string.tools_city_not_found)
                CityMessage.FAILED -> stringResource(R.string.tools_city_failed)
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (message == CityMessage.SAVED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

internal fun Context.safeStart(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // 设备上没有对应页面，忽略
    }
}
