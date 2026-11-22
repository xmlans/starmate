package org.xmyun.starmate.feature.chat.chat

import java.util.Optional
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.ChatDraftBus
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.CompanionProfile
import org.xmyun.starmate.core.model.CompanionStore
import org.xmyun.starmate.core.model.ConfirmDecision
import org.xmyun.starmate.core.model.ConfirmRequest
import org.xmyun.starmate.core.model.ConfirmationBroker
import org.xmyun.starmate.core.model.ConversationEngine
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.MemoryWriter
import org.xmyun.starmate.core.model.MessageStatus
import org.xmyun.starmate.core.model.MessageStore
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.ProviderStore
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TurnEvent
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UserInput
import org.xmyun.starmate.core.ui.contract.AttachmentRenderer
import org.xmyun.starmate.core.ui.contract.ChatInputAction
import org.xmyun.starmate.core.ui.contract.PermissionInvite
import org.xmyun.starmate.feature.chat.common.ChatPrefs
import org.xmyun.starmate.feature.chat.interview.InterviewCoordinator
import org.xmyun.starmate.feature.chat.onboarding.OnboardingSteps

/** 工具活动小标签。 */
data class ToolActivity(val callId: String, val name: String, val summary: String, val running: Boolean, val isError: Boolean)

/** 列表里的一条消息，已经叠加了正在生成的文字和工具活动。 */
data class MessageItem(
    val message: ChatMessage,
    val text: String,
    val tools: List<ToolActivity>,
    val streaming: Boolean,
    val failed: Boolean,
    val errorKind: LlmErrorKind?,
) {
    val id: Long get() = message.id
    val isUser: Boolean get() = message.role == Role.USER
}

/** 进入聊天页要不要先走引导。 */
sealed interface ChatGate {
    data object Loading : ChatGate
    data class NeedsOnboarding(val route: String) : ChatGate
    data object Ready : ChatGate
}

sealed interface ChatEvent {
    /** 轻提示，例如上一轮还没结束又发了一条。 */
    data object Busy : ChatEvent
    data object ShowPermissionInvites : ChatEvent
}

private data class LiveTurn(
    val assistantId: Long = 0,
    val text: String = "",
    val finished: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messages: MessageStore,
    private val engine: ConversationEngine,
    private val broker: ConfirmationBroker,
    private val draftBus: ChatDraftBus,
    companions: CompanionStore,
    private val prefs: CompanionPrefs,
    providers: ProviderStore,
    private val coordinator: InterviewCoordinator,
    private val chatPrefs: ChatPrefs,
    renderers: Set<@JvmSuppressWildcards AttachmentRenderer>,
    inputActions: Set<@JvmSuppressWildcards ChatInputAction>,
    private val invites: Set<@JvmSuppressWildcards PermissionInvite>,
    private val memoryWriter: Optional<MemoryWriter> = Optional.empty(),
) : ViewModel() {

    val renderers: Map<String, AttachmentRenderer> = renderers.associateBy { it.type }
    val inputActions: List<ChatInputAction> = inputActions.sortedWith(compareBy({ it.order }, { it.javaClass.name }))

    private val limit = MutableStateFlow(PAGE_SIZE)
    private val live = MutableStateFlow<LiveTurn?>(null)
    private val toolsByMessage = MutableStateFlow<Map<Long, List<ToolActivity>>>(emptyMap())
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _draft = MutableStateFlow<Attachment?>(null)
    val draft: StateFlow<Attachment?> = _draft.asStateFlow()

    private val _events = Channel<ChatEvent>(Channel.BUFFERED)
    val events: Flow<ChatEvent> = _events.receiveAsFlow()

    val companion: StateFlow<CompanionProfile?> =
        companions.profile.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val onboardingState: StateFlow<OnboardingState?> =
        prefs.onboardingState.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val extracting: StateFlow<Boolean> = coordinator.extracting

    val pendingConfirm: StateFlow<ConfirmRequest?> = broker.pending

    val gate: StateFlow<ChatGate> = combine(
        prefs.adultConfirmed,
        providers.assignments.map { ModelRole.CHAT in it },
        prefs.onboardingState,
    ) { adult, hasChat, state ->
        OnboardingSteps.firstStep(adult, hasChat, state)?.let { ChatGate.NeedsOnboarding(it) } ?: ChatGate.Ready
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatGate.Loading)

    private val rawMessages: StateFlow<List<ChatMessage>> = limit
        .flatMapLatest { messages.observeRecent(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val items: StateFlow<List<MessageItem>> = combine(rawMessages, live, toolsByMessage, _sending) { list, turn, tools, sending ->
        list.mapNotNull { message -> toItem(message, turn, tools, sending) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 还没有任何文字可显示时显示“正在输入”。 */
    val typing: StateFlow<Boolean> = combine(_sending, live, toolsByMessage) { sending, turn, tools ->
        sending && turn?.text.isNullOrEmpty() && turn?.let { tools[it.assistantId] }.orEmpty().none { it.running }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 列表是否可能还有更早的消息。 */
    val canLoadMore: StateFlow<Boolean> = combine(rawMessages, limit) { list, l -> list.size >= l }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var sendJob: Job? = null
    private var kickoffChecked = false

    init {
        viewModelScope.launch {
            gate.first { it is ChatGate.Ready }
            maybeKickoff()
        }
        viewModelScope.launch {
            // 小结已落盘（暂存清空）且访谈结束后，出现一次权限邀请页
            combine(prefs.onboardingState, prefs.pendingOnboardingProfile, chatPrefs.invitesShown, gate) { state, pending, shown, gate ->
                gate is ChatGate.Ready && state == OnboardingState.DONE && pending == null && !shown
            }.first { it }
            // 只出现一次：从邀请页按系统返回键离开也算看过
            chatPrefs.setInvitesShown(true)
            if (invites.isNotEmpty()) _events.send(ChatEvent.ShowPermissionInvites)
        }
    }

    private fun toItem(
        message: ChatMessage,
        turn: LiveTurn?,
        tools: Map<Long, List<ToolActivity>>,
        sending: Boolean,
    ): MessageItem? {
        val isLive = turn != null && turn.assistantId == message.id && message.status == MessageStatus.STREAMING
        val text = if (isLive) turn.text else message.displayText
        val messageTools = tools[message.id].orEmpty()
        // 流式消息在引擎没跑时还停在 STREAMING，说明上次被打断了，也给重试
        val stale = message.status == MessageStatus.STREAMING && !isLive && !sending
        val failed = message.status == MessageStatus.FAILED || stale
        val streaming = message.status == MessageStatus.STREAMING && !stale
        // 没有渲染器的附件类型忽略
        val hasRenderable = message.attachments.any { it.type in renderers }
        if (!failed && text.isBlank() && !hasRenderable && messageTools.isEmpty()) return null
        return MessageItem(
            message = message,
            text = text,
            tools = messageTools,
            streaming = streaming,
            failed = failed,
            errorKind = if (failed) message.errorKind ?: LlmErrorKind.UNKNOWN else null,
        )
    }

    /** 每次进入页面时调用：取走其他页面塞过来的待发附件。 */
    fun onEnter() {
        draftBus.consume()?.let { _draft.value = it }
    }

    fun onInputChange(text: String) {
        _input.value = text
    }

    fun insertText(text: String) {
        _input.update { current -> if (current.isBlank()) text else "$current $text" }
    }

    fun clearDraft() {
        _draft.value = null
    }

    fun loadMore() {
        if (canLoadMore.value) limit.update { it + PAGE_SIZE }
    }

    fun send() {
        val text = _input.value.trim()
        val attachment = _draft.value
        if (text.isEmpty() && attachment == null) return
        if (_sending.value) {
            _events.trySend(ChatEvent.Busy)
            return
        }
        _input.value = ""
        _draft.value = null
        runTurn(UserInput.Text(text, listOfNotNull(attachment)))
    }

    fun retry(assistantMessageId: Long) {
        if (_sending.value) {
            _events.trySend(ChatEvent.Busy)
            return
        }
        runTurn(UserInput.Retry(assistantMessageId))
    }

    fun delete(messageId: Long) {
        viewModelScope.launch { messages.delete(messageId) }
    }

    /**
     * 举报她的一条回复：从聊天里删除（之后也不会作为历史发给模型），再从记忆的原话里抹掉。
     * 发给开发者的邮件由界面另行打开，这里不联网。
     */
    fun report(message: ChatMessage) {
        viewModelScope.launch {
            messages.delete(message.id)
            try {
                memoryWriter.orElse(null)?.forgetReply(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 记忆模块出错不影响举报本身：回复已经从聊天里删掉了
            }
        }
    }

    fun approve(request: ConfirmRequest, remember: Boolean) {
        broker.resolve(request.id, ConfirmDecision(approved = true, remember = remember && request.allowRemember))
    }

    fun deny(request: ConfirmRequest) {
        broker.resolve(request.id, ConfirmDecision(approved = false))
    }

    fun skipInterview() {
        coordinator.skipAsync()
    }

    fun markInvitesShown() {
        viewModelScope.launch { chatPrefs.setInvitesShown(true) }
    }

    private suspend fun maybeKickoff() {
        if (kickoffChecked) return
        kickoffChecked = true
        if (prefs.onboardingState.first() != OnboardingState.INTERVIEWING) return
        val started = messages.recent(KICKOFF_SCAN_LIMIT).any { it.purpose == TurnPurpose.ONBOARDING }
        if (!started && !_sending.value) runTurn(UserInput.Kickoff(TurnPurpose.ONBOARDING))
    }

    private fun runTurn(input: UserInput) {
        _sending.value = true
        live.value = LiveTurn()
        sendJob = viewModelScope.launch {
            try {
                engine.send(input).collect { event -> handle(event) }
            } finally {
                live.update { it?.copy(finished = true) }
                toolsByMessage.update { map ->
                    map.mapValues { (_, list) -> list.map { if (it.running) it.copy(running = false) else it } }
                }
                _sending.value = false
            }
        }
    }

    private suspend fun handle(event: TurnEvent) {
        when (event) {
            is TurnEvent.Started -> live.value = LiveTurn(assistantId = event.assistantMessageId)
            is TurnEvent.Text -> live.update { (it ?: LiveTurn()).copy(text = event.displaySoFar) }
            is TurnEvent.ToolStarted -> {
                val id = live.value?.assistantId ?: return
                val activity = ToolActivity(event.callId, event.toolName, event.summary, running = true, isError = false)
                toolsByMessage.update { it + (id to (it[id].orEmpty() + activity)) }
            }
            is TurnEvent.ToolFinished -> {
                val id = live.value?.assistantId ?: return
                toolsByMessage.update { map ->
                    val updated = map[id].orEmpty().map {
                        if (it.callId == event.callId) it.copy(running = false, isError = event.isError) else it
                    }
                    map + (id to updated)
                }
            }
            is TurnEvent.Completed -> live.update { it?.copy(finished = true) }
            is TurnEvent.Failed -> {
                live.update { it?.copy(finished = true) }
                // 0 表示没有对应的助手消息（例如上一轮还没结束），只给轻提示
                if (event.assistantMessageId == 0L) _events.send(ChatEvent.Busy)
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 50
        private const val KICKOFF_SCAN_LIMIT = 500
    }
}
