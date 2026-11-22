package org.xmyun.starmate.feature.voice.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.xmyun.starmate.core.ui.component.ConfirmDialog
import org.xmyun.starmate.core.ui.component.SettingsChevron
import org.xmyun.starmate.core.ui.component.SettingsDivider
import org.xmyun.starmate.core.ui.component.SettingsRow
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.core.ui.component.SettingsSwitchRow
import org.xmyun.starmate.feature.voice.R
import org.xmyun.starmate.feature.voice.prefs.VoiceFrequency
import org.xmyun.starmate.feature.voice.prefs.VoicePrefs

@Composable
fun VoiceSettingsScreen(
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    viewModel: VoiceSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showFrequency by rememberSaveable { mutableStateOf(false) }
    var showLimit by rememberSaveable { mutableStateOf(false) }
    var confirmClean by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                VoiceSettingsMessage.SampleUnavailable -> context.getString(R.string.voice_sample_unavailable)
                VoiceSettingsMessage.SampleFailed -> context.getString(R.string.voice_sample_failed)
                is VoiceSettingsMessage.Cleaned -> if (message.count == 0) {
                    context.getString(R.string.voice_cache_nothing)
                } else {
                    context.resources.getQuantityString(R.plurals.voice_cache_cleaned, message.count, message.count)
                }
            }
            snackbar.showSnackbar(text)
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.voice_settings_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(title = stringResource(R.string.voice_section_messages)) {
                if (state.ttsModel == null) {
                    SettingsRow(
                        icon = Icons.Outlined.Info,
                        title = stringResource(R.string.voice_tts_missing_title),
                        summary = stringResource(
                            if (state.systemFallback) R.string.voice_tts_missing_fallback else R.string.voice_tts_missing_summary,
                        ),
                        onClick = onOpenModels,
                        trailing = { SettingsChevron() },
                    )
                } else {
                    SettingsRow(
                        icon = Icons.Outlined.RecordVoiceOver,
                        title = stringResource(R.string.voice_tts_model),
                        summary = state.ttsModel,
                        onClick = onOpenModels,
                        trailing = { SettingsChevron() },
                    )
                }
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Outlined.GraphicEq,
                    title = stringResource(R.string.voice_frequency),
                    summary = stringResource(frequencyLabel(state.frequency)),
                    onClick = { showFrequency = true },
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Outlined.Speed,
                    title = stringResource(R.string.voice_daily_limit),
                    summary = stringResource(R.string.voice_daily_limit_summary, state.dailyLimit, state.usedToday),
                    onClick = { showLimit = true },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = stringResource(R.string.voice_system_fallback),
                    summary = stringResource(R.string.voice_system_fallback_summary),
                    checked = state.systemFallback,
                    onCheckedChange = viewModel::setSystemFallback,
                )
                SettingsDivider()
                val sampleText = stringResource(R.string.voice_sample_text)
                SettingsRow(
                    icon = Icons.Outlined.PlayCircle,
                    title = stringResource(R.string.voice_sample),
                    summary = stringResource(R.string.voice_sample_summary),
                    onClick = { viewModel.playSample(sampleText) },
                    enabled = !state.sampleLoading,
                    trailing = if (state.sampleLoading) {
                        { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                    } else {
                        null
                    },
                )
            }

            SettingsSection(title = stringResource(R.string.voice_section_input)) {
                SettingsRow(
                    icon = Icons.Outlined.Mic,
                    title = stringResource(R.string.voice_input_engine),
                    summary = state.sttModel ?: stringResource(R.string.voice_input_system),
                    onClick = onOpenModels,
                    trailing = { SettingsChevron() },
                )
            }

            SettingsSection(title = stringResource(R.string.voice_section_storage)) {
                SettingsRow(
                    icon = Icons.Outlined.Storage,
                    title = stringResource(R.string.voice_cache_size),
                    summary = Formatter.formatShortFileSize(context, state.cacheBytes),
                )
                SettingsDivider()
                SettingsRow(
                    icon = Icons.Outlined.CleaningServices,
                    title = stringResource(R.string.voice_cache_clean),
                    summary = stringResource(R.string.voice_cache_clean_summary),
                    onClick = { confirmClean = true },
                    enabled = !state.cleaning,
                )
            }
        }
    }

    if (showFrequency) {
        ChoiceDialog(
            title = stringResource(R.string.voice_frequency),
            options = VoiceFrequency.entries,
            selected = state.frequency,
            label = { stringResource(frequencyLabel(it)) },
            onSelect = {
                viewModel.setFrequency(it)
                showFrequency = false
            },
            onDismiss = { showFrequency = false },
        )
    }

    if (showLimit) {
        val choices = (VoicePrefs.DAILY_LIMIT_CHOICES + state.dailyLimit).distinct().sorted()
        ChoiceDialog(
            title = stringResource(R.string.voice_daily_limit),
            options = choices,
            selected = state.dailyLimit,
            label = { stringResource(R.string.voice_daily_limit_value, it) },
            onSelect = {
                viewModel.setDailyLimit(it)
                showLimit = false
            },
            onDismiss = { showLimit = false },
        )
    }

    if (confirmClean) {
        ConfirmDialog(
            title = stringResource(R.string.voice_cache_clean_confirm_title),
            text = stringResource(R.string.voice_cache_clean_confirm_text),
            confirmLabel = stringResource(R.string.voice_cache_clean_confirm),
            onConfirm = {
                confirmClean = false
                viewModel.cleanOld()
            },
            onDismiss = { confirmClean = false },
            destructive = true,
            icon = Icons.Outlined.CleaningServices,
        )
    }
}

private fun frequencyLabel(frequency: VoiceFrequency): Int = when (frequency) {
    VoiceFrequency.NEVER -> R.string.voice_frequency_never
    VoiceFrequency.SOMETIMES -> R.string.voice_frequency_sometimes
    VoiceFrequency.OFTEN -> R.string.voice_frequency_often
    VoiceFrequency.ALWAYS -> R.string.voice_frequency_always
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_dialog_cancel)) }
        },
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    val isSelected = option == selected
                    ListItem(
                        headlineContent = { Text(label(option)) },
                        leadingContent = { RadioButton(selected = isSelected, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) }),
                    )
                }
            }
        },
    )
}
