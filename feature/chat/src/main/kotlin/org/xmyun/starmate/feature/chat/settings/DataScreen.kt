package org.xmyun.starmate.feature.chat.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmyun.starmate.core.model.DataArchiver
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.ui.component.ConfirmDialog
import org.xmyun.starmate.core.ui.component.SettingsDivider
import org.xmyun.starmate.core.ui.component.SettingsRow
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.feature.chat.R

@HiltViewModel
class DataViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val archiver: DataArchiver,
    private val clock: Clock,
    private val diag: DiagLog,
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = Channel<Int>(Channel.BUFFERED)
    val messages: Flow<Int> = _messages.receiveAsFlow()

    fun suggestedName(): String =
        "starmate-backup-${LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE)}.zip"

    fun export(uri: Uri) = runArchive(R.string.chat_data_export_done) {
        val stream = context.contentResolver.openOutputStream(uri) ?: throw IOException("cannot open")
        stream.use { archiver.export(it) }
    }

    fun import(uri: Uri) = runArchive(R.string.chat_data_import_done) {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open")
        stream.use { archiver.import(it) }
    }

    private fun runArchive(doneMessage: Int, block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { block() }
                doneMessage
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diag.log("chat.data", "archive failed: ${e.javaClass.simpleName}")
                when (e) {
                    is IllegalArgumentException -> R.string.chat_restore_error_invalid
                    is IOException -> R.string.chat_restore_error_io
                    else -> R.string.chat_restore_error_unknown
                }
            } finally {
                _busy.value = false
            }
            _messages.send(result)
        }
    }
}

/** 导入导出：用系统文件选择器拿到流交给 DataArchiver。导入前二次确认。 */
@Composable
fun DataScreen(viewModel: DataViewModel, onBack: () -> Unit) {
    val busy by viewModel.busy.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(context.getString(it)) }
    }

    SettingsScaffold(
        title = stringResource(R.string.chat_data_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                SettingsSection(title = null) {
                    SettingsRow(
                        icon = Icons.Outlined.Upload,
                        title = stringResource(R.string.chat_data_export),
                        summary = stringResource(R.string.chat_data_export_summary),
                        onClick = { exporter.launch(viewModel.suggestedName()) },
                        enabled = !busy,
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Outlined.Download,
                        title = stringResource(R.string.chat_data_import),
                        summary = stringResource(R.string.chat_data_import_summary),
                        onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
                        enabled = !busy,
                        trailing = if (busy) {
                            { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp)) }
                        } else {
                            null
                        },
                    )
                }
                Text(
                    stringResource(R.string.chat_data_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                )
            }
        }
    }

    pendingImport?.let { uri ->
        ConfirmDialog(
            title = stringResource(R.string.chat_data_import_confirm_title),
            text = stringResource(R.string.chat_data_import_confirm_text),
            confirmLabel = stringResource(R.string.chat_data_import),
            destructive = true,
            onConfirm = {
                pendingImport = null
                viewModel.import(uri)
            },
            onDismiss = { pendingImport = null },
        )
    }
}
