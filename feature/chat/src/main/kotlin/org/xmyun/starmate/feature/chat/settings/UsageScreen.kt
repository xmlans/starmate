package org.xmyun.starmate.feature.chat.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.DailyUsage
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UsageLedger
import org.xmyun.starmate.core.ui.component.EmptyState
import org.xmyun.starmate.core.ui.component.SettingsDivider
import org.xmyun.starmate.core.ui.component.SettingsRow
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.feature.chat.R

data class BudgetRow(val purpose: TurnPurpose, val budget: Int, val usedToday: Int)

data class UsageDay(val date: LocalDate, val rows: List<DailyUsage>)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UsageViewModel @Inject constructor(
    private val ledger: UsageLedger,
    private val prefs: CompanionPrefs,
) : ViewModel() {

    private val daily = ledger.observeDaily(DAYS)

    val days: StateFlow<List<UsageDay>> = daily.map { list ->
        list.groupBy { it.date }
            .toSortedMap(compareByDescending { it })
            .map { (date, rows) -> UsageDay(date, rows.sortedBy { it.purpose.ordinal }) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 各后台用途今天的预算和已用量；账本一有新记录就刷新已用量。 */
    val budgets: StateFlow<List<BudgetRow>> = combine(BACKGROUND.map { prefs.dailyBudget(it) }) { it.toList() }
        .flatMapLatest { budgets ->
            daily.map {
                BACKGROUND.mapIndexed { i, purpose -> BudgetRow(purpose, budgets[i], ledger.usedToday(purpose)) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setBudget(purpose: TurnPurpose, tokens: Int) {
        viewModelScope.launch { prefs.setDailyBudget(purpose, tokens.coerceIn(0, MAX_BUDGET)) }
    }

    companion object {
        const val DAYS = 30
        const val MAX_BUDGET = 10_000_000
        val BACKGROUND = listOf(TurnPurpose.MEMORY, TurnPurpose.PROACTIVE, TurnPurpose.DIARY)
    }
}

/** 用量统计：近 30 天按日期和用途列出 token 与 TTS 字符数；后台用途今天的预算可调。 */
@Composable
fun UsageScreen(viewModel: UsageViewModel, onBack: () -> Unit) {
    val days by viewModel.days.collectAsState()
    val budgets by viewModel.budgets.collectAsState()
    var editing by remember { mutableStateOf<BudgetRow?>(null) }
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    SettingsScaffold(title = stringResource(R.string.chat_usage_title), onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                SettingsSection(title = stringResource(R.string.chat_usage_budget_section)) {
                    budgets.forEachIndexed { index, row ->
                        if (index > 0) SettingsDivider()
                        Column(Modifier.fillMaxWidth()) {
                            SettingsRow(
                                icon = null,
                                title = stringResource(purposeLabel(row.purpose)),
                                summary = stringResource(R.string.chat_usage_budget_value, numbers.format(row.usedToday), numbers.format(row.budget)),
                                onClick = { editing = row },
                            )
                            LinearProgressIndicator(
                                progress = { if (row.budget <= 0) 1f else (row.usedToday.toFloat() / row.budget).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.chat_usage_budget_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                )
            }
            if (days.isEmpty()) {
                item {
                    EmptyState(icon = Icons.Outlined.QueryStats, title = stringResource(R.string.chat_usage_empty))
                }
            }
            items(days, key = { it.date.toString() }) { day ->
                SettingsSection(title = day.date.format(dateFormat)) {
                    day.rows.forEachIndexed { index, row ->
                        if (index > 0) SettingsDivider()
                        val summary = buildString {
                            append(stringResource(R.string.chat_usage_tokens, numbers.format(row.inputTokens), numbers.format(row.outputTokens)))
                            if (row.ttsChars > 0) {
                                append(" · ")
                                append(stringResource(R.string.chat_usage_tts, numbers.format(row.ttsChars)))
                            }
                        }
                        SettingsRow(icon = null, title = stringResource(purposeLabel(row.purpose)), summary = summary)
                    }
                }
            }
        }
    }

    editing?.let { row ->
        BudgetDialog(
            row = row,
            onSave = { value ->
                viewModel.setBudget(row.purpose, value)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun BudgetDialog(row: BudgetRow, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(row.budget.toString()) }
    val value = text.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(purposeLabel(row.purpose))) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(8) },
                label = { Text(stringResource(R.string.chat_usage_budget_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onSave) }, enabled = value != null) { Text(stringResource(R.string.chat_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(org.xmyun.starmate.core.ui.R.string.core_cancel)) }
        },
    )
}

fun purposeLabel(purpose: TurnPurpose): Int = when (purpose) {
    TurnPurpose.CHAT -> R.string.chat_purpose_chat
    TurnPurpose.ONBOARDING -> R.string.chat_purpose_onboarding
    TurnPurpose.PROACTIVE -> R.string.chat_purpose_proactive
    TurnPurpose.DIARY -> R.string.chat_purpose_diary
    TurnPurpose.MEMORY -> R.string.chat_purpose_memory
    TurnPurpose.MOBILE_USE -> R.string.chat_purpose_mobile_use
}
