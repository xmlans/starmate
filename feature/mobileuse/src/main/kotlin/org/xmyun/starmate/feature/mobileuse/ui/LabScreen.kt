package org.xmyun.starmate.feature.mobileuse.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import kotlin.math.roundToInt
import org.xmyun.starmate.core.ui.component.SettingsChevron
import org.xmyun.starmate.core.ui.component.SettingsDivider
import org.xmyun.starmate.core.ui.component.SettingsRow
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.core.ui.component.SettingsSwitchRow
import org.xmyun.starmate.feature.mobileuse.R
import org.xmyun.starmate.feature.mobileuse.agent.TaskLimits

/** 实验室页：开关、状态、去开启无障碍服务、受限设置说明、白名单、日志、单次任务上限。 */
@Composable
fun LabScreen(
    onBack: () -> Unit,
    onOpenRisk: () -> Unit,
    onOpenAllowlist: () -> Unit,
    onOpenLog: () -> Unit,
    viewModel: LabViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SettingsScaffold(title = stringResource(R.string.mobileuse_settings_title), onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Text(
                    text = stringResource(R.string.mobileuse_lab_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }
            item {
                SettingsSection(title = null) {
                    SettingsSwitchRow(
                        icon = Icons.Outlined.TouchApp,
                        title = stringResource(R.string.mobileuse_lab_enable),
                        summary = when {
                            !state.sdkSupported -> stringResource(R.string.mobileuse_lab_needs_android11)
                            !state.riskAccepted -> stringResource(R.string.mobileuse_lab_enable_needs_risk)
                            else -> stringResource(R.string.mobileuse_lab_enable_summary)
                        },
                        checked = state.enabled && state.riskAccepted,
                        enabled = state.sdkSupported,
                        onCheckedChange = { on ->
                            if (on && !state.riskAccepted) onOpenRisk() else viewModel.setEnabled(on)
                        },
                    )
                    if (state.running) {
                        SettingsDivider()
                        SettingsRow(
                            icon = Icons.Outlined.StopCircle,
                            title = stringResource(R.string.mobileuse_lab_stop_running),
                            onClick = viewModel::stopTask,
                        )
                    }
                }
            }
            item {
                SettingsSection(title = stringResource(R.string.mobileuse_lab_section_setup)) {
                    SettingsRow(
                        icon = Icons.Outlined.Accessibility,
                        title = stringResource(R.string.mobileuse_lab_service),
                        summary = stringResource(
                            if (state.serviceConnected) R.string.mobileuse_lab_service_on else R.string.mobileuse_lab_service_off,
                        ),
                        onClick = { openSafely(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        trailing = { SettingsChevron() },
                        enabled = state.sdkSupported,
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Outlined.Info,
                        title = stringResource(R.string.mobileuse_lab_restricted_title),
                        summary = stringResource(R.string.mobileuse_lab_restricted_summary),
                        onClick = {
                            openSafely(
                                context,
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                            )
                        },
                        trailing = { SettingsChevron() },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Outlined.Apps,
                        title = stringResource(R.string.mobileuse_allowlist_title),
                        summary = if (state.allowlistSize == 0) {
                            stringResource(R.string.mobileuse_lab_allowlist_empty)
                        } else {
                            stringResource(R.string.mobileuse_lab_allowlist_count, state.allowlistSize)
                        },
                        onClick = onOpenAllowlist,
                        trailing = { SettingsChevron() },
                    )
                }
            }
            item {
                SettingsSection(title = stringResource(R.string.mobileuse_lab_section_limits)) {
                    LimitsEditor(limits = state.limits, onChange = viewModel::setLimits)
                }
            }
            item {
                SettingsSection(title = stringResource(R.string.mobileuse_lab_section_more)) {
                    SettingsRow(
                        icon = Icons.Outlined.History,
                        title = stringResource(R.string.mobileuse_log_title),
                        summary = stringResource(R.string.mobileuse_lab_log_summary),
                        onClick = onOpenLog,
                        trailing = { SettingsChevron() },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Outlined.Shield,
                        title = stringResource(R.string.mobileuse_risk_title),
                        summary = stringResource(
                            if (state.riskAccepted) R.string.mobileuse_lab_risk_accepted else R.string.mobileuse_lab_risk_unread,
                        ),
                        onClick = onOpenRisk,
                        trailing = { SettingsChevron() },
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Outlined.PhoneAndroid,
                        title = stringResource(R.string.mobileuse_lab_how_to_stop),
                        summary = stringResource(R.string.mobileuse_lab_how_to_stop_summary),
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun LimitsEditor(limits: TaskLimits, onChange: (TaskLimits) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        LimitSlider(
            label = stringResource(R.string.mobileuse_lab_limit_steps, limits.maxSteps),
            value = limits.maxSteps.toFloat(),
            range = 5f..TaskLimits.DEFAULT_STEPS.toFloat(),
            steps = TaskLimits.DEFAULT_STEPS - 6,
            onDone = { onChange(limits.copy(maxSteps = it.roundToInt())) },
        )
        LimitSlider(
            label = stringResource(R.string.mobileuse_lab_limit_minutes, limits.maxDuration.toMinutes().toInt()),
            value = limits.maxDuration.toMinutes().toFloat(),
            range = 1f..TaskLimits.DEFAULT_DURATION.toMinutes().toFloat(),
            steps = TaskLimits.DEFAULT_DURATION.toMinutes().toInt() - 2,
            onDone = { onChange(limits.copy(maxDuration = Duration.ofMinutes(it.roundToInt().toLong()))) },
        )
        LimitSlider(
            label = stringResource(R.string.mobileuse_lab_limit_tokens, limits.maxTokens / 1000),
            value = (limits.maxTokens / 10_000).toFloat(),
            range = (TaskLimits.MIN_TOKENS / 10_000).toFloat()..(TaskLimits.MAX_TOKENS / 10_000).toFloat(),
            steps = (TaskLimits.MAX_TOKENS - TaskLimits.MIN_TOKENS) / 10_000 - 1,
            onDone = { onChange(limits.copy(maxTokens = it.roundToInt() * 10_000)) },
        )
        Text(
            text = stringResource(R.string.mobileuse_lab_limits_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LimitSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onDone: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Text(text = label, style = MaterialTheme.typography.bodyLarge)
    Slider(
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = { onDone(current) },
        valueRange = range,
        steps = steps.coerceAtLeast(0),
    )
}

private fun openSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // 个别系统没有对应页面，忽略
    }
}
