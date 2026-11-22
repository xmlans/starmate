package org.xmyun.starmate.feature.chat.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import org.xmyun.starmate.core.model.DiagEntry
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.ui.component.EmptyState
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.feature.chat.R

@HiltViewModel
class DiagViewModel @Inject constructor(diag: DiagLog) : ViewModel() {
    val entries: StateFlow<List<DiagEntry>> = diag.entries
}

/** 诊断日志（底座已对 key 打码）。最新的在上面，可整体复制。 */
@Composable
fun DiagScreen(viewModel: DiagViewModel, onBack: () -> Unit) {
    val entries by viewModel.entries.collectAsState()
    val context = LocalContext.current
    val formatter = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()) }
    val newestFirst = remember(entries) { entries.asReversed() }

    SettingsScaffold(
        title = stringResource(R.string.chat_diag_title),
        onBack = onBack,
        actions = {
            IconButton(
                onClick = {
                    val text = entries.joinToString("\n") { "${formatter.format(it.at)} [${it.tag}] ${it.message}" }
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Starmate diagnostics", text))
                },
                enabled = entries.isNotEmpty(),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.chat_diag_copy))
            }
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                title = stringResource(R.string.chat_diag_empty),
                modifier = Modifier.padding(padding),
            )
            return@SettingsScaffold
        }
        SelectionContainer {
            LazyColumn(contentPadding = padding) {
                items(newestFirst.size) { index ->
                    val entry = newestFirst[index]
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            "${formatter.format(entry.at)}  ${entry.tag}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(entry.message, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
