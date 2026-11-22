package org.xmyun.starmate.feature.chat

import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.ChatDraftBus
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.CompanionProfile
import org.xmyun.starmate.core.model.CompanionStore
import org.xmyun.starmate.core.model.ConfirmDecision
import org.xmyun.starmate.core.model.ConfirmRequest
import org.xmyun.starmate.core.model.ConfirmationBroker
import org.xmyun.starmate.core.model.ConnectionTest
import org.xmyun.starmate.core.model.ConversationEngine
import org.xmyun.starmate.core.model.DiagEntry
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.HeadlessResult
import org.xmyun.starmate.core.model.HeadlessTurn
import org.xmyun.starmate.core.model.MemoryWriter
import org.xmyun.starmate.core.model.MessageStore
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.ProviderConfig
import org.xmyun.starmate.core.model.ProviderKind
import org.xmyun.starmate.core.model.ProviderPreset
import org.xmyun.starmate.core.model.ProviderStore
import org.xmyun.starmate.core.model.QuietHours
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.RoleAssignment
import org.xmyun.starmate.core.model.TokenUsage
import org.xmyun.starmate.core.model.TurnEvent
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UserInput
import org.xmyun.starmate.feature.chat.common.ChatPrefs
import org.xmyun.starmate.feature.chat.interview.InterviewTexts

class FakeCompanionPrefs(
    state: OnboardingState = OnboardingState.NOT_STARTED,
    adult: Boolean = false,
) : CompanionPrefs {
    /** 按调用顺序记录写操作，用来检查先后。 */
    val calls = mutableListOf<String>()

    val adultFlow = MutableStateFlow(adult)
    val stateFlow = MutableStateFlow(state)
    val pendingFlow = MutableStateFlow<String?>(null)
    val quietFlow = MutableStateFlow(QuietHours.DEFAULT)
    val clingFlow = MutableStateFlow(Clinginess.MEDIUM)
    val budgets = mutableMapOf<TurnPurpose, MutableStateFlow<Int>>()
    val historyFlow = MutableStateFlow(8000)

    override val adultConfirmed: Flow<Boolean> = adultFlow
    override suspend fun setAdultConfirmed(value: Boolean) { calls += "adult"; adultFlow.value = value }
    override val onboardingState: Flow<OnboardingState> = stateFlow
    override suspend fun setOnboardingState(state: OnboardingState) { calls += "state:$state"; stateFlow.value = state }
    override val pendingOnboardingProfile: Flow<String?> = pendingFlow
    override suspend fun setPendingOnboardingProfile(json: String?) { calls += if (json == null) "pending:null" else "pending:set"; pendingFlow.value = json }
    override val quietHours: Flow<QuietHours> = quietFlow
    override suspend fun setQuietHours(value: QuietHours) { calls += "quiet"; quietFlow.value = value }
    override val clinginess: Flow<Clinginess> = clingFlow
    override suspend fun setClinginess(value: Clinginess) { calls += "cling"; clingFlow.value = value }
    override fun dailyBudget(purpose: TurnPurpose): Flow<Int> = budgets.getOrPut(purpose) { MutableStateFlow(10000) }
    override suspend fun setDailyBudget(purpose: TurnPurpose, tokens: Int) { budgets.getOrPut(purpose) { MutableStateFlow(0) }.value = tokens }
    override val historyTokenBudget: Flow<Int> = historyFlow
    override suspend fun setHistoryTokenBudget(tokens: Int) { historyFlow.value = tokens }
}

class FakeCompanionStore(
    initial: CompanionProfile = CompanionProfile(name = "小星", persona = "温柔"),
) : CompanionStore {
    val flow = MutableStateFlow(initial)
    var updates = 0
    override val profile: Flow<CompanionProfile> = flow
    override suspend fun current(): CompanionProfile = flow.value
    override suspend fun update(transform: (CompanionProfile) -> CompanionProfile) {
        updates++
        flow.value = transform(flow.value)
    }
}

class FakeMessageStore : MessageStore {
    val all = MutableStateFlow<List<ChatMessage>>(emptyList())
    private var nextId = 1L
    var clock: Instant = Instant.parse("2026-11-12T10:00:00Z")

    private fun tick(): Instant { clock = clock.plusSeconds(1); return clock }

    override fun observeRecent(limit: Int): Flow<List<ChatMessage>> = all.map { it.takeLast(limit) }
    override suspend fun recent(limit: Int): List<ChatMessage> = all.value.takeLast(limit)
    override suspend fun between(from: Instant, to: Instant): List<ChatMessage> =
        all.value.filter { it.createdAt >= from && it.createdAt < to }
    override suspend fun byId(id: Long): ChatMessage? = all.value.firstOrNull { it.id == id }
    override suspend fun insert(message: ChatMessage): Long {
        val id = nextId++
        all.value = all.value + message.copy(id = id, createdAt = tick())
        return id
    }
    override suspend fun update(message: ChatMessage) {
        all.value = all.value.map { if (it.id == message.id) message else it }
    }
    override suspend fun delete(id: Long) {
        all.value = all.value.filterNot { it.id == id }
    }
    override suspend fun lastMessageAt(role: Role?): Instant? =
        all.value.lastOrNull { role == null || it.role == role }?.createdAt

    suspend fun addUser(text: String, purpose: TurnPurpose): Long = insert(
        ChatMessage(role = Role.USER, rawText = text, displayText = text, purpose = purpose, createdAt = clock),
    )

    suspend fun addAssistant(text: String, purpose: TurnPurpose): Long = insert(
        ChatMessage(role = Role.ASSISTANT, rawText = text, displayText = text, purpose = purpose, createdAt = clock),
    )
}

/** 引擎假实现：send 按脚本发事件；runHeadless 记录调用并返回预设结果。 */
class FakeEngine : ConversationEngine {
    val sent = mutableListOf<UserInput>()
    val headless = mutableListOf<HeadlessTurn>()
    var headlessResult: HeadlessResult = HeadlessResult.Success("{}", TokenUsage())

    /** 每次 send 时调用，返回这一轮要发出的事件流。 */
    var script: (UserInput) -> Flow<TurnEvent> = { flow { } }

    private val busy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = busy

    override fun send(input: UserInput): Flow<TurnEvent> {
        sent += input
        return script(input)
    }

    override suspend fun runHeadless(turn: HeadlessTurn): HeadlessResult {
        headless += turn
        return headlessResult
    }

    override suspend fun deliver(rawText: String, purpose: TurnPurpose): ChatMessage =
        throw UnsupportedOperationException()
}

class FakeMemoryWriter : MemoryWriter {
    data class Entry(val text: String, val category: String, val importance: Int)
    val entries = mutableListOf<Entry>()
    override suspend fun add(text: String, category: String, importance: Int) {
        entries += Entry(text, category, importance)
    }
}

class FakeTexts(override val chinese: Boolean = true) : InterviewTexts {
    override fun interviewPrompt(): String = "INTERVIEW PROMPT"
    override fun extractionPrompt(): String = "EXTRACT PROMPT"
    override fun summaryPreview(): String = "我记住了这些"
}

class FakeDiag : DiagLog {
    val lines = mutableListOf<String>()
    override fun log(tag: String, message: String) { lines += "$tag: $message" }
    override val entries: StateFlow<List<DiagEntry>> = MutableStateFlow(emptyList())
}

class FakeBroker : ConfirmationBroker {
    val pendingFlow = MutableStateFlow<ConfirmRequest?>(null)
    val resolved = mutableListOf<Pair<String, ConfirmDecision>>()
    override val pending: StateFlow<ConfirmRequest?> = pendingFlow
    override suspend fun confirm(request: ConfirmRequest): ConfirmDecision = throw UnsupportedOperationException()
    override fun resolve(requestId: String, decision: ConfirmDecision) {
        resolved += requestId to decision
        pendingFlow.value = null
    }
}

class FakeDraftBus : ChatDraftBus {
    private val flow = MutableStateFlow<Attachment?>(null)
    override val pending: StateFlow<Attachment?> = flow.asStateFlow()
    override fun offer(attachment: Attachment) { flow.value = attachment }
    override fun consume(): Attachment? = flow.value.also { flow.value = null }
}

class FakeChatPrefs : ChatPrefs {
    val flow = MutableStateFlow(false)
    override val invitesShown: Flow<Boolean> = flow
    override suspend fun setInvitesShown(value: Boolean) { flow.value = value }
}

class FakeProviderStore : ProviderStore {
    val saved = mutableListOf<Pair<ProviderConfig, String?>>()
    val assigned = mutableListOf<RoleAssignment>()
    val tested = mutableListOf<Pair<String, String>>()
    var testResult: ConnectionTest = ConnectionTest.Ok
    var models: List<String> = listOf("gpt-a", "gpt-b")

    override val presets: List<ProviderPreset> = listOf(
        ProviderPreset("openai", "OpenAI", ProviderKind.OPENAI_COMPAT, "https://api.openai.com/v1"),
        ProviderPreset("deepseek", "DeepSeek", ProviderKind.OPENAI_COMPAT, "https://api.deepseek.com/v1"),
    )
    val providerFlow = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val assignmentFlow = MutableStateFlow<Map<ModelRole, RoleAssignment>>(emptyMap())
    override val providers: Flow<List<ProviderConfig>> = providerFlow
    override val assignments: Flow<Map<ModelRole, RoleAssignment>> = assignmentFlow

    override suspend fun saveProvider(config: ProviderConfig, apiKey: String?) {
        saved += config to apiKey
        providerFlow.value = providerFlow.value.filterNot { it.id == config.id } + config
    }
    override suspend fun deleteProvider(id: String) {
        providerFlow.value = providerFlow.value.filterNot { it.id == id }
    }
    override suspend fun assign(assignment: RoleAssignment) {
        assigned += assignment
        assignmentFlow.value = assignmentFlow.value + (assignment.role to assignment)
    }
    override suspend fun unassign(role: ModelRole) {
        assignmentFlow.value = assignmentFlow.value - role
    }
    override suspend fun listModels(providerId: String): List<String> = models
    override suspend fun test(providerId: String, model: String): ConnectionTest {
        tested += providerId to model
        return testResult
    }
}

/** 一个永远不会自己结束的事件流，配合 Channel 逐个推事件。 */
class EventPipe {
    val channel = Channel<TurnEvent>(Channel.UNLIMITED)
    fun flow(): Flow<TurnEvent> = channel.receiveAsFlow()
}
