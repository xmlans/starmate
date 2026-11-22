package org.xmyun.starmate.feature.chat.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Duration
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.ConfirmRequest
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.ui.component.Avatar
import org.xmyun.starmate.core.ui.component.ConfirmDialog
import org.xmyun.starmate.core.ui.component.GlassScaffold
import org.xmyun.starmate.core.ui.component.GlassTopBar
import org.xmyun.starmate.core.ui.component.StarryWallpaper
import org.xmyun.starmate.core.ui.contract.AttachmentRenderer
import org.xmyun.starmate.core.ui.contract.ChatInputAction
import org.xmyun.starmate.core.ui.contract.LocalAppEdition
import org.xmyun.starmate.core.ui.glass.LocalBottomBarInset
import org.xmyun.starmate.core.ui.theme.PillShape
import org.xmyun.starmate.feature.chat.R

/**
 * 聊天页。进入时先看要不要走引导；访谈期间右上角有“跳过”。
 * 这里只负责把 ViewModel 的状态接到 [ChatContent] 上。
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onStartOnboarding: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onShowPermissionInvites: () -> Unit,
) {
    val gate by viewModel.gate.collectAsState()
    LaunchedEffect(gate) {
        (gate as? ChatGate.NeedsOnboarding)?.let { onStartOnboarding(it.route) }
    }
    if (gate !is ChatGate.Ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    LaunchedEffect(Unit) { viewModel.onEnter() }

    val context = LocalContext.current
    val items by viewModel.items.collectAsState()
    val typing by viewModel.typing.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val input by viewModel.input.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val companion by viewModel.companion.collectAsState()
    val state by viewModel.onboardingState.collectAsState()
    val extracting by viewModel.extracting.collectAsState()
    val confirm by viewModel.pendingConfirm.collectAsState()
    val canLoadMore by viewModel.canLoadMore.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val busyText = stringResource(R.string.chat_notice_busy)
    val copiedText = stringResource(R.string.chat_copied)
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    val edition = LocalAppEdition.current
    var pendingReport by remember { mutableStateOf<MessageItem?>(null) }
    val reportedText = stringResource(R.string.chat_report_done)
    val noEmailText = stringResource(R.string.chat_report_no_email_app, edition.contactEmail)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ChatEvent.Busy -> snackbar.showSnackbar(busyText)
                ChatEvent.ShowPermissionInvites -> onShowPermissionInvites()
            }
        }
    }

    ChatContent(
        name = companion?.name.orEmpty(),
        avatar = companion?.avatarUri,
        items = items,
        typing = typing,
        sending = sending,
        input = input,
        draft = draft,
        interviewing = state == OnboardingState.INTERVIEWING,
        extracting = extracting,
        confirm = confirm,
        canLoadMore = canLoadMore,
        renderers = viewModel.renderers,
        inputActions = viewModel.inputActions,
        snackbar = snackbar,
        onLoadMore = viewModel::loadMore,
        onInputChange = viewModel::onInputChange,
        onInsertText = viewModel::insertText,
        onSend = viewModel::send,
        onClearDraft = viewModel::clearDraft,
        onSkipInterview = viewModel::skipInterview,
        onApprove = viewModel::approve,
        onDeny = viewModel::deny,
        onCopy = { text ->
            copyToClipboard(context, text)
            // Android 13 起系统自己会提示已复制
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                scope.launch { snackbar.showSnackbar(copiedText) }
            }
        },
        onDelete = { pendingDelete = it },
        onRetry = viewModel::retry,
        onFix = onNavigate,
        onReport = if (edition.contentReports) ({ pendingReport = it }) else null,
    )

    pendingReport?.let { item ->
        ReportDialog(
            onConfirm = { reason, sendToDeveloper ->
                pendingReport = null
                viewModel.report(item.message)
                val mailed = !sendToDeveloper || ReportMail.open(context, edition, reason, item.text)
                scope.launch { snackbar.showSnackbar(if (mailed) reportedText else noEmailText) }
            },
            onDismiss = { pendingReport = null },
        )
    }

    pendingDelete?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.chat_delete_title),
            text = stringResource(R.string.chat_delete_text),
            confirmLabel = stringResource(R.string.chat_action_delete),
            destructive = true,
            onConfirm = {
                viewModel.delete(id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 聊天页的界面，不依赖 ViewModel。
 *
 * 消息铺在星空壁纸上，从毛玻璃的标题栏和输入框下面滚过去。
 * 列表倒序排布（最新的在底部且始终贴底），滚到顶端时调用 onLoadMore 加载更早的消息。
 */
@Composable
fun ChatContent(
    name: String,
    avatar: String?,
    items: List<MessageItem>,
    typing: Boolean,
    sending: Boolean,
    input: String,
    draft: Attachment?,
    interviewing: Boolean,
    extracting: Boolean,
    confirm: ConfirmRequest?,
    canLoadMore: Boolean,
    renderers: Map<String, AttachmentRenderer>,
    inputActions: List<ChatInputAction>,
    snackbar: SnackbarHostState,
    onLoadMore: () -> Unit,
    onInputChange: (String) -> Unit,
    onInsertText: (String) -> Unit,
    onSend: () -> Unit,
    onClearDraft: () -> Unit,
    onSkipInterview: () -> Unit,
    onApprove: (ConfirmRequest, Boolean) -> Unit,
    onDeny: (ConfirmRequest) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onFix: (String) -> Unit,
    /** 不为 null 时，她的回复长按菜单里有“举报”。 */
    onReport: ((MessageItem) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val reversed = remember(items) { items.asReversed() }
    // 倒序列表里，最后一个可见项接近末尾就是快滚到最早的消息了
    val nearTop by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(listState, canLoadMore) {
        snapshotFlow { nearTop }.collect { if (it && canLoadMore) onLoadMore() }
    }
    // 自己发出新消息时回到底部
    LaunchedEffect(sending) {
        if (sending) listState.animateScrollToItem(0)
    }

    val barInset = LocalBottomBarInset.current
    GlassScaffold(
        topBar = { glass ->
            GlassTopBar(
                state = glass,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(uri = avatar, name = name, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.semantics { heading() },
                            )
                            if (typing || sending) {
                                Text(
                                    stringResource(R.string.chat_typing),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (interviewing) {
                        TextButton(onClick = onSkipInterview, enabled = !extracting) {
                            Text(stringResource(R.string.chat_skip_interview))
                        }
                    }
                },
                below = {
                    if (extracting) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { glass ->
            // 输入栏浮在壁纸上：键盘弹出时跟着键盘走，收起时停在系统导航条和底部标签栏上方。
            val restingInset = WindowInsets.navigationBars.add(WindowInsets(bottom = barInset))
            Column(Modifier.windowInsetsPadding(WindowInsets.ime.union(restingInset))) {
                confirm?.let { request ->
                    ConfirmCard(
                        request = request,
                        onApprove = { remember -> onApprove(request, remember) },
                        onDeny = { onDeny(request) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                InputBar(
                    glass = glass,
                    text = input,
                    onTextChange = onInputChange,
                    onSend = onSend,
                    sendEnabled = !sending && (input.isNotBlank() || draft != null),
                    draft = draft,
                    onClearDraft = onClearDraft,
                    renderers = renderers,
                    actions = inputActions,
                    onInsertText = onInsertText,
                )
            }
        },
        background = { StarryWallpaper() },
    ) { padding ->
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 4.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (typing) {
                item(key = "typing") {
                    TypingRow(companionName = name, companionAvatar = avatar, modifier = Modifier.padding(top = 6.dp))
                }
            }
            items(reversed.size, key = { reversed[it].id }) { index ->
                val item = reversed[index]
                val older = reversed.getOrNull(index + 1)
                val newer = reversed.getOrNull(index - 1)
                // 她连续的几条消息只在最后一条旁边放头像
                val showAvatar = if (newer == null) !typing else newer.isUser
                val gap = older?.let { Duration.between(it.message.createdAt, item.message.createdAt) }
                val roleChanged = older != null && older.isUser != item.isUser
                Column(Modifier.padding(top = if (roleChanged) 8.dp else 0.dp)) {
                    if (older == null || gap != null && gap > TIME_LABEL_GAP) {
                        TimeLabel(epochMillis = item.message.createdAt.toEpochMilli())
                    }
                    MessageRow(
                        item = item,
                        companionName = name,
                        companionAvatar = avatar,
                        showAvatar = showAvatar && !item.isUser,
                        renderers = renderers,
                        onCopy = onCopy,
                        onDelete = { onDelete(item.id) },
                        onRetry = { onRetry(item.id) },
                        onFix = onFix,
                        onReport = if (onReport != null && !item.isUser) ({ onReport(item) }) else null,
                    )
                }
            }
        }
    }
}

/** 压在壁纸上的时间小胶囊：半透明的页面底色，保证时间在任何主题色的壁纸上都看得清。 */
@Composable
private fun TimeLabel(epochMillis: Long) {
    val context = LocalContext.current
    val text = remember(epochMillis) {
        DateUtils.formatDateTime(
            context,
            epochMillis,
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), PillShape)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Starmate", text))
}

private const val LOAD_MORE_THRESHOLD = 5
private val TIME_LABEL_GAP: Duration = Duration.ofMinutes(60)
