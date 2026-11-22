package org.xmyun.starmate.feature.proactive.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DoNotDisturb
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.ui.component.ConfirmDialog
import org.xmyun.starmate.core.ui.component.SettingsChevron
import org.xmyun.starmate.core.ui.component.SettingsDivider
import org.xmyun.starmate.core.ui.component.SettingsRow
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.contract.LocalAppEdition
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.core.ui.component.SettingsSwitchRow
import org.xmyun.starmate.feature.proactive.R
import org.xmyun.starmate.feature.proactive.gate.DenyReason
import org.xmyun.starmate.feature.proactive.model.Care
import org.xmyun.starmate.feature.proactive.model.LogOutcome
import org.xmyun.starmate.feature.proactive.model.LogReasons
import org.xmyun.starmate.feature.proactive.model.LogSource
import org.xmyun.starmate.feature.proactive.model.SendLogEntry
import org.xmyun.starmate.feature.proactive.notify.NotificationPermission
import org.xmyun.starmate.feature.proactive.notify.rememberNotificationPermissionRequester

private enum class QuietEdge { START, END }

@Composable
fun ProactiveSettingsScreen(
    onBack: () -> Unit,
    onOpenBatteryGuide: () -> Unit,
    viewModel: ProactiveSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notificationsAllowed by viewModel.notificationsAllowed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.refreshNotificationAccess()
        onPauseOrDispose { }
    }
    val requestNotifications = rememberNotificationPermissionRequester { granted ->
        // 被拒绝（或系统不再弹窗、或低版本被用户关掉）时带用户去系统设置
        if (!granted || !NotificationPermission.isGranted(context)) NotificationPermission.openSettings(context)
        viewModel.refreshNotificationAccess()
    }
    var editing by remember { mutableStateOf<QuietEdge?>(null) }
    var cancelling by remember { mutableStateOf<Care?>(null) }

    SettingsScaffold(title = stringResource(R.string.proactive_settings_title), onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            if (state.loaded && state.enabled && !notificationsAllowed) {
                item {
                    NotificationBanner(
                        onEnable = {
                            if (NotificationPermission.isGranted(context)) {
                                NotificationPermission.openSettings(context)
                            } else {
                                requestNotifications()
                            }
                        },
                    )
                }
            }
            item {
                SettingsSection(title = null) {
                    SettingsSwitchRow(
                        icon = Icons.Outlined.NotificationsActive,
                        title = stringResource(R.string.proactive_master_title),
                        summary = stringResource(R.string.proactive_master_summary),
                        checked = state.enabled,
                        onCheckedChange = viewModel::setEnabled,
                    )
                }
            }
            item { ClinginessSection(selected = state.clinginess, onSelect = viewModel::setClinginess) }
            item {
                SettingsSection(title = stringResource(R.string.proactive_section_quiet)) {
                    SettingsRow(
                        icon = Icons.Outlined.Bedtime,
                        title = stringResource(R.string.proactive_quiet_start),
                        summary = formatMinute(state.quietHours.startMinute),
                        onClick = { editing = QuietEdge.START },
                        trailing = { SettingsChevron() },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Outlined.WbSunny,
                        title = stringResource(R.string.proactive_quiet_end),
                        summary = formatMinute(state.quietHours.endMinute),
                        onClick = { editing = QuietEdge.END },
                        trailing = { SettingsChevron() },
                    )
                    Text(
                        text = stringResource(R.string.proactive_quiet_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    )
                }
            }
            item {
                CaresSection(
                    cares = state.cares,
                    paused = state.caresPaused,
                    zone = viewModel.zone,
                    onCancel = { cancelling = it },
                )
            }
            item { LogSection(logs = state.logs, zone = viewModel.zone) }
            item {
                SettingsSection(title = stringResource(R.string.proactive_section_background)) {
                    // Play 版不带保活服务，不显示这个开关
                    if (LocalAppEdition.current.keepAlive) {
                        SettingsSwitchRow(
                            icon = Icons.Outlined.Shield,
                            title = stringResource(R.string.proactive_keepalive_title),
                            summary = stringResource(R.string.proactive_keepalive_summary),
                            checked = state.keepAlive,
                            onCheckedChange = viewModel::setKeepAlive,
                        )
                        SettingsDivider()
                    }
                    SettingsRow(
                        icon = Icons.Outlined.BatteryChargingFull,
                        title = stringResource(R.string.proactive_battery_entry),
                        summary = stringResource(R.string.proactive_battery_entry_summary),
                        onClick = onOpenBatteryGuide,
                        trailing = { SettingsChevron() },
                    )
                }
            }
        }
    }

    editing?.let { edge ->
        val initial = if (edge == QuietEdge.START) state.quietHours.startMinute else state.quietHours.endMinute
        QuietTimeDialog(
            title = stringResource(if (edge == QuietEdge.START) R.string.proactive_quiet_start else R.string.proactive_quiet_end),
            initialMinute = initial,
            onConfirm = { minute ->
                if (edge == QuietEdge.START) viewModel.setQuietStart(minute) else viewModel.setQuietEnd(minute)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    cancelling?.let { care ->
        ConfirmDialog(
            title = stringResource(R.string.proactive_care_cancel_title),
            text = stringResource(R.string.proactive_care_cancel_text, care.topic),
            confirmLabel = stringResource(R.string.proactive_care_cancel_confirm),
            onConfirm = {
                viewModel.cancelCare(care.id)
                cancelling = null
            },
            onDismiss = { cancelling = null },
            dismissLabel = stringResource(R.string.proactive_care_keep),
            destructive = true,
        )
    }
}

@Composable
private fun NotificationBanner(onEnable: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.NotificationsOff, contentDescription = null)
            Text(stringResource(R.string.proactive_notif_banner_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.proactive_notif_banner_text), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onEnable) { Text(stringResource(R.string.proactive_notif_banner_button)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClinginessSection(selected: Clinginess, onSelect: (Clinginess) -> Unit) {
    val options = Clinginess.entries
    SettingsSection(title = stringResource(R.string.proactive_section_frequency)) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(stringResource(clinginessLabel(option)))
                }
            }
        }
        val summary = if (selected == Clinginess.OFF) {
            stringResource(R.string.proactive_clinginess_off_summary)
        } else {
            stringResource(R.string.proactive_clinginess_summary, selected.maxPerDay)
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun CaresSection(cares: List<Care>, paused: Boolean, zone: ZoneId, onCancel: (Care) -> Unit) {
    SettingsSection(title = stringResource(R.string.proactive_section_cares)) {
        if (cares.isEmpty()) {
            SettingsRow(icon = Icons.Outlined.Schedule, title = stringResource(R.string.proactive_cares_empty))
        } else if (paused) {
            Text(
                text = stringResource(R.string.proactive_cares_paused_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        val pausedLabel = stringResource(R.string.proactive_care_paused)
        cares.forEachIndexed { index, care ->
            if (index > 0) SettingsDivider()
            val time = stringResource(R.string.proactive_care_when, formatInstant(care.scheduledFor, zone))
            val detail = if (care.reason.isBlank()) time else "$time · ${care.reason}"
            SettingsRow(
                icon = if (paused) Icons.Outlined.PauseCircle else Icons.Outlined.Schedule,
                title = care.topic,
                summary = if (paused) "$pausedLabel · $detail" else detail,
                trailing = {
                    IconButton(onClick = { onCancel(care) }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.proactive_care_cancel_cd, care.topic),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun LogSection(logs: List<SendLogEntry>, zone: ZoneId) {
    SettingsSection(title = stringResource(R.string.proactive_section_log)) {
        if (logs.isEmpty()) {
            SettingsRow(icon = Icons.Outlined.HourglassEmpty, title = stringResource(R.string.proactive_log_empty))
        }
        logs.forEachIndexed { index, entry ->
            if (index > 0) SettingsDivider()
            val title = when (entry.source) {
                LogSource.CHECK_IN -> stringResource(R.string.proactive_log_source_checkin)
                LogSource.CARE -> stringResource(R.string.proactive_log_source_care, entry.topic.orEmpty())
            }
            val outcome = stringResource(outcomeLabel(entry.outcome))
            val detail = if (entry.outcome == LogOutcome.SENT) {
                entry.text.orEmpty()
            } else {
                reasonLabel(entry.reason)?.let { stringResource(it) } ?: entry.reason.orEmpty()
            }
            val separator = stringResource(R.string.proactive_log_detail_separator)
            val summary = buildString {
                append(formatInstant(entry.at, zone))
                append(" · ")
                append(outcome)
                if (detail.isNotBlank()) {
                    append(separator)
                    append(detail)
                }
            }
            SettingsRow(icon = outcomeIcon(entry.outcome), title = title, summary = summary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietTimeDialog(title: String, initialMinute: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour * 60 + pickerState.minute) }) {
                Text(stringResource(R.string.proactive_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.proactive_cancel)) }
        },
        title = { Text(title) },
        text = { TimePicker(state = pickerState) },
    )
}

private fun formatMinute(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

private val SHORT_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

private fun formatInstant(at: Instant, zone: ZoneId): String = at.atZone(zone).format(SHORT_DATE_TIME)

@StringRes
private fun clinginessLabel(value: Clinginess): Int = when (value) {
    Clinginess.OFF -> R.string.proactive_clinginess_off
    Clinginess.LOW -> R.string.proactive_clinginess_low
    Clinginess.MEDIUM -> R.string.proactive_clinginess_medium
    Clinginess.HIGH -> R.string.proactive_clinginess_high
}

@StringRes
private fun outcomeLabel(outcome: LogOutcome): Int = when (outcome) {
    LogOutcome.SENT -> R.string.proactive_outcome_sent
    LogOutcome.BLOCKED -> R.string.proactive_outcome_blocked
    LogOutcome.DECLINED -> R.string.proactive_outcome_declined
    LogOutcome.FAILED -> R.string.proactive_outcome_failed
    LogOutcome.GAVE_UP -> R.string.proactive_outcome_gave_up
}

private fun outcomeIcon(outcome: LogOutcome) = when (outcome) {
    LogOutcome.SENT -> Icons.Outlined.CheckCircle
    LogOutcome.BLOCKED -> Icons.Outlined.Block
    LogOutcome.DECLINED -> Icons.Outlined.DoNotDisturb
    LogOutcome.FAILED -> Icons.Outlined.ErrorOutline
    LogOutcome.GAVE_UP -> Icons.Outlined.HourglassEmpty
}

@StringRes
internal fun reasonLabel(reason: String?): Int? = when (reason) {
    null -> null
    DenyReason.DISABLED.name -> R.string.proactive_reason_disabled
    DenyReason.NO_NETWORK.name -> R.string.proactive_reason_no_network
    DenyReason.QUIET_HOURS.name -> R.string.proactive_reason_quiet_hours
    DenyReason.DAILY_CAP.name -> R.string.proactive_reason_daily_cap
    DenyReason.TOO_SOON.name -> R.string.proactive_reason_too_soon
    DenyReason.UNANSWERED.name -> R.string.proactive_reason_unanswered
    DenyReason.CHATTING.name -> R.string.proactive_reason_chatting
    DenyReason.BUDGET.name -> R.string.proactive_reason_budget
    LogReasons.NOT_IDLE -> R.string.proactive_reason_not_idle
    LogReasons.NO_HISTORY -> R.string.proactive_reason_no_history
    LogReasons.CHAT_STARTED -> R.string.proactive_reason_chat_started
    LogReasons.MODEL_DECLINED -> R.string.proactive_reason_model_declined
    LogReasons.UNPARSEABLE -> R.string.proactive_reason_unparseable
    LogReasons.DELIVER_ERROR -> R.string.proactive_reason_deliver_error
    LogReasons.EXPIRED -> R.string.proactive_reason_expired
    LlmErrorKind.NOT_CONFIGURED.name -> R.string.proactive_reason_llm_not_configured
    LlmErrorKind.INVALID_KEY.name -> R.string.proactive_reason_llm_invalid_key
    LlmErrorKind.QUOTA_OR_RATE_LIMIT.name -> R.string.proactive_reason_llm_quota
    LlmErrorKind.MODEL_NOT_FOUND.name -> R.string.proactive_reason_llm_model_not_found
    LlmErrorKind.UNREACHABLE.name -> R.string.proactive_reason_llm_unreachable
    LlmErrorKind.TIMEOUT.name -> R.string.proactive_reason_llm_timeout
    else -> R.string.proactive_reason_llm_other
}
