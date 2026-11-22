package org.xmyun.starmate.feature.mobileuse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.ui.component.ConfirmDialog
import org.xmyun.starmate.core.ui.component.EmptyState
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.feature.mobileuse.R
import org.xmyun.starmate.feature.mobileuse.log.RoomActionLogger
import org.xmyun.starmate.feature.mobileuse.log.StepEntity
import org.xmyun.starmate.feature.mobileuse.log.TaskEntity

@HiltViewModel
class ActionLogViewModel @Inject constructor(
    private val logger: RoomActionLogger,
) : ViewModel() {
    val tasks: StateFlow<List<TaskEntity>?> =
        logger.observeTasks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val expanded = MutableStateFlow<Long?>(null)
    val expandedId: StateFlow<Long?> = expanded

    @OptIn(ExperimentalCoroutinesApi::class)
    val steps: StateFlow<List<StepEntity>> = expanded
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else logger.observeSteps(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggle(taskId: Long) {
        expanded.value = if (expanded.value == taskId) null else taskId
    }

    fun clear() {
        expanded.value = null
        viewModelScope.launch { logger.clear() }
    }
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val STEP_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun format(ms: Long, f: DateTimeFormatter): String =
    f.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** 操作日志页：每次任务的每一步（时间、应用、动作、守卫结论）。 */
@Composable
fun ActionLogScreen(onBack: () -> Unit, viewModel: ActionLogViewModel = hiltViewModel()) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val expandedId by viewModel.expandedId.collectAsStateWithLifecycle()
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.mobileuse_log_title),
        onBack = onBack,
        actions = {
            if (!tasks.isNullOrEmpty()) {
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.mobileuse_log_clear))
                }
            }
        },
    ) { padding ->
        val list = tasks
        if (list != null && list.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.mobileuse_log_empty),
                description = stringResource(R.string.mobileuse_log_empty_desc),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(contentPadding = padding) {
                items(list.orEmpty(), key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        expanded = expandedId == task.id,
                        steps = if (expandedId == task.id) steps else emptyList(),
                        onClick = { viewModel.toggle(task.id) },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.mobileuse_log_clear_title),
            text = stringResource(R.string.mobileuse_log_clear_text),
            confirmLabel = stringResource(R.string.mobileuse_log_clear),
            onConfirm = {
                viewModel.clear()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
            destructive = true,
        )
    }
}

@Composable
private fun TaskCard(task: TaskEntity, expanded: Boolean, steps: List<StepEntity>, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(
            Modifier
                .clickable(onClick = onClick)
                .padding(16.dp),
        ) {
            Text(text = task.goal, style = MaterialTheme.typography.titleSmall, maxLines = if (expanded) 10 else 2)
            Spacer(Modifier.height(4.dp))
            Text(
                text = format(task.startedAt, TIME) + " · " + outcomeLabel(task.outcome),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                task.detail?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (steps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.mobileuse_log_no_steps),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                steps.forEach { step -> StepRow(step) }
            }
        }
    }
}

@Composable
private fun StepRow(step: StepEntity) {
    Column(Modifier.padding(top = 8.dp)) {
        val app = step.appLabel ?: step.packageName ?: "—"
        Text(
            text = format(step.at, STEP_TIME) + " · " + app,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = step.action + " · " + verdictLabel(step.verdict), style = MaterialTheme.typography.bodyMedium)
        step.detail?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun outcomeLabel(code: String?): String = when {
    code == null -> stringResource(R.string.mobileuse_log_outcome_running)
    code == "DONE" -> stringResource(R.string.mobileuse_log_outcome_done)
    code.startsWith("HALT_") -> stringResource(R.string.mobileuse_log_outcome_halt, code.removePrefix("HALT_"))
    code.startsWith("LIMIT_") -> stringResource(R.string.mobileuse_log_outcome_limit)
    code.startsWith("STOP_") -> stringResource(R.string.mobileuse_log_outcome_stopped)
    else -> stringResource(R.string.mobileuse_log_outcome_failed)
}

@Composable
private fun verdictLabel(verdict: String): String = when (verdict) {
    "ALLOW" -> stringResource(R.string.mobileuse_verdict_allow)
    "ALLOW_FAILED" -> stringResource(R.string.mobileuse_verdict_failed)
    "CONFIRM" -> stringResource(R.string.mobileuse_verdict_confirm)
    "HALT" -> stringResource(R.string.mobileuse_verdict_halt)
    "USER_APPROVED" -> stringResource(R.string.mobileuse_verdict_user_approved)
    "USER_DECLINED" -> stringResource(R.string.mobileuse_verdict_user_declined)
    "USER_YES" -> stringResource(R.string.mobileuse_verdict_user_yes)
    "USER_NO" -> stringResource(R.string.mobileuse_verdict_user_no)
    "DONE" -> stringResource(R.string.mobileuse_verdict_done)
    else -> verdict
}
