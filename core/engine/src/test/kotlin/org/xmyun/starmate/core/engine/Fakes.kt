package org.xmyun.starmate.core.engine

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import org.xmyun.starmate.core.engine.guidelines.Guidelines
import org.xmyun.starmate.core.engine.guidelines.GuidelinesSource
import org.xmyun.starmate.core.model.AmbientSource
import org.xmyun.starmate.core.model.AppState
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.CompanionProfile
import org.xmyun.starmate.core.model.CompanionStore
import org.xmyun.starmate.core.model.ConfirmDecision
import org.xmyun.starmate.core.model.ConfirmRequest
import org.xmyun.starmate.core.model.ConfirmationBroker
import org.xmyun.starmate.core.model.DailyUsage
import org.xmyun.starmate.core.model.DiagEntry
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.EmbeddingClient
import org.xmyun.starmate.core.model.EndpointConfig
import org.xmyun.starmate.core.model.LlmClient
import org.xmyun.starmate.core.model.LlmEvent
import org.xmyun.starmate.core.model.LlmRequest
import org.xmyun.starmate.core.model.MessageStore
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ModelRouter
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.PromptContributor
import org.xmyun.starmate.core.model.QuietHours
import org.xmyun.starmate.core.model.ReplyPostProcessor
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.StopReason
import org.xmyun.starmate.core.model.TokenUsage
import org.xmyun.starmate.core.model.Tool
import org.xmyun.starmate.core.model.ToolCall
import org.xmyun.starmate.core.model.ToolConsentStore
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.model.ToolResult
import org.xmyun.starmate.core.model.ToolRisk
import org.xmyun.starmate.core.model.TurnListener
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UsageEntry
import org.xmyun.starmate.core.model.UsageLedger

val DEFAULT_USAGE = TokenUsage(inputTokens = 10, outputTokens = 5)

fun textReply(vararg chunks: String, usage: TokenUsage = DEFAULT_USAGE): Flow<LlmEvent> =
    flowOf(*(chunks.map { LlmEvent.TextDelta(it) } + LlmEvent.Completed(usage, StopReason.END)).toTypedArray())

fun toolCallReply(vararg calls: ToolCall, text: String = ""): Flow<LlmEvent> {
    val events = mutableListOf<LlmEvent>()
    if (text.isNotEmpty()) events += LlmEvent.TextDelta(text)
    calls.forEach { events += LlmEvent.ToolCallRequested(it) }
    events += LlmEvent.Completed(DEFAULT_USAGE, StopReason.TOOL_CALLS)
    return flowOf(*events.toTypedArray())
}

class FakeLlmClient(
    override val modelId: String = "fake-model",
    override var supportsTools: Boolean = true,
    private val script: (request: LlmRequest, index: Int) -> Flow<LlmEvent> = { _, _ -> textReply("好") },
) : LlmClient {
    val requests = mutableListOf<LlmRequest>()
    override val supportsImages: Boolean = false

    override fun stream(request: LlmRequest): Flow<LlmEvent> {
        val index = requests.size
        requests += request
        return script(request, index)
    }
}

class MutableClock(
    var now: Instant = Instant.parse("2026-11-11T13:05:00Z"),
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
) : Clock() {
    override fun getZone(): ZoneId = zoneId
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

class FakeMessageStore : MessageStore {
    private val items = MutableStateFlow<List<ChatMessage>>(emptyList())
    private var nextId = 1L

    val all: List<ChatMessage>
        get() = items.value.sortedWith(compareBy({ it.createdAt }, { it.id }))

    override fun observeRecent(limit: Int): Flow<List<ChatMessage>> =
        items.map { list -> list.sortedWith(compareBy({ it.createdAt }, { it.id })).takeLast(limit) }

    override suspend fun recent(limit: Int): List<ChatMessage> = all.takeLast(limit)

    override suspend fun between(from: Instant, to: Instant): List<ChatMessage> =
        all.filter { it.createdAt >= from && it.createdAt < to }

    override suspend fun byId(id: Long): ChatMessage? = items.value.firstOrNull { it.id == id }

    override suspend fun insert(message: ChatMessage): Long {
        val id = nextId++
        items.value = items.value + message.copy(id = id)
        return id
    }

    override suspend fun update(message: ChatMessage) {
        items.value = items.value.map { if (it.id == message.id) message else it }
    }

    override suspend fun delete(id: Long) {
        items.value = items.value.filterNot { it.id == id }
    }

    override suspend fun lastMessageAt(role: Role?): Instant? =
        all.lastOrNull { role == null || it.role == role }?.createdAt
}

class FakeCompanionStore(
    initial: CompanionProfile = CompanionProfile(
        name = "小星",
        persona = "温柔、爱笑的女孩。",
        relationshipNotes = "喜欢被叫醒时轻一点。",
        userNickname = "阿远",
    ),
) : CompanionStore {
    private val state = MutableStateFlow(initial)
    override val profile: Flow<CompanionProfile> = state
    override suspend fun current(): CompanionProfile = state.value
    override suspend fun update(transform: (CompanionProfile) -> CompanionProfile) {
        state.value = transform(state.value)
    }
}

class FakeCompanionPrefs : CompanionPrefs {
    override val adultConfirmed = MutableStateFlow(true)
    override suspend fun setAdultConfirmed(value: Boolean) {
        adultConfirmed.value = value
    }

    override val onboardingState = MutableStateFlow(OnboardingState.DONE)
    override suspend fun setOnboardingState(state: OnboardingState) {
        onboardingState.value = state
    }

    override val pendingOnboardingProfile = MutableStateFlow<String?>(null)
    override suspend fun setPendingOnboardingProfile(json: String?) {
        pendingOnboardingProfile.value = json
    }

    override val quietHours = MutableStateFlow(QuietHours.DEFAULT)
    override suspend fun setQuietHours(value: QuietHours) {
        quietHours.value = value
    }

    override val clinginess = MutableStateFlow(Clinginess.MEDIUM)
    override suspend fun setClinginess(value: Clinginess) {
        clinginess.value = value
    }

    private val budgets = mutableMapOf<TurnPurpose, MutableStateFlow<Int>>()
    override fun dailyBudget(purpose: TurnPurpose): Flow<Int> = budgets.getOrPut(purpose) { MutableStateFlow(10_000) }
    override suspend fun setDailyBudget(purpose: TurnPurpose, tokens: Int) {
        budgets.getOrPut(purpose) { MutableStateFlow(tokens) }.value = tokens
    }

    override val historyTokenBudget = MutableStateFlow(8000)
    override suspend fun setHistoryTokenBudget(tokens: Int) {
        historyTokenBudget.value = tokens
    }
}

class FakeUsageLedger : UsageLedger {
    val records = mutableListOf<UsageEntry>()
    val blocked = mutableSetOf<TurnPurpose>()

    override suspend fun record(entry: UsageEntry) {
        records += entry
    }

    override suspend fun usedToday(purpose: TurnPurpose): Int =
        records.filter { it.purpose == purpose }.sumOf { it.inputTokens + it.outputTokens }

    override suspend fun allow(purpose: TurnPurpose): Boolean = purpose !in blocked

    override fun observeDaily(days: Int): Flow<List<DailyUsage>> = emptyFlow()
}

class FakeModelRouter(var client: LlmClient?) : ModelRouter {
    val requestedRoles = mutableListOf<ModelRole>()
    val markedUnsupported = mutableListOf<ModelRole>()

    override suspend fun chat(role: ModelRole): LlmClient? {
        requestedRoles += role
        return client
    }

    override suspend fun embedding(): EmbeddingClient? = null
    override suspend fun endpoint(role: ModelRole): EndpointConfig? = null
    override suspend fun markToolsUnsupported(role: ModelRole) {
        markedUnsupported += role
        (client as? FakeLlmClient)?.supportsTools = false
    }
}

class FakeDiagLog : DiagLog {
    private val _entries = MutableStateFlow<List<DiagEntry>>(emptyList())
    override val entries: StateFlow<List<DiagEntry>> = _entries.asStateFlow()
    override fun log(tag: String, message: String) {
        _entries.value = _entries.value + DiagEntry(Instant.EPOCH, tag, message)
    }
}

class FakeAppState(foreground: Boolean = true) : AppState {
    override val isForeground = MutableStateFlow(foreground)
}

class FakeGuidelines(
    private val text: String = "【内置准则】先听懂再回应。",
    private val chinese: Boolean = true,
) : GuidelinesSource {
    override fun load() = Guidelines(text, chinese)
}

class InMemoryToolConsentStore : ToolConsentStore {
    private val state = MutableStateFlow<Set<String>>(emptySet())
    override val remembered: Flow<Set<String>> = state
    override suspend fun isRemembered(toolName: String) = toolName in state.value
    override suspend fun remember(toolName: String) {
        state.value = state.value + toolName
    }
    override suspend fun forget(toolName: String) {
        state.value = state.value - toolName
    }
}

/** 按脚本直接给出决定的确认器，记录被问过的请求。 */
class ScriptedBroker(
    var decide: (ConfirmRequest) -> ConfirmDecision = { ConfirmDecision(approved = true) },
) : ConfirmationBroker {
    val asked = mutableListOf<ConfirmRequest>()
    override val pending: StateFlow<ConfirmRequest?> = MutableStateFlow(null)
    override suspend fun confirm(request: ConfirmRequest): ConfirmDecision {
        asked += request
        return decide(request)
    }
    override fun resolve(requestId: String, decision: ConfirmDecision) = Unit
}

class FakeTool(
    override val name: String,
    override val risk: ToolRisk = ToolRisk.READ,
    override val allowRememberConfirm: Boolean = true,
    override val returnsUntrustedContent: Boolean = false,
    override val confirmAfterUntrusted: Boolean = false,
    private val confirmText: String? = null,
    private val action: suspend (JsonObject) -> ToolResult = { ToolResult("ok:$name") },
) : Tool {
    val calls = mutableListOf<JsonObject>()
    override val description: String = "fake tool $name"
    override val parametersSchema: JsonObject = JsonObject(emptyMap())
    override fun describeCall(args: JsonObject): String = "调用 $name"
    override fun describeConfirm(args: JsonObject): String = confirmText ?: describeCall(args)
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        calls += args
        return action(args)
    }
}

class FakeToolProvider(private vararg val tools: Tool) : ToolProvider {
    val purposes = mutableListOf<TurnPurpose>()
    override suspend fun tools(purpose: TurnPurpose): List<Tool> {
        purposes += purpose
        return tools.toList()
    }
}

class FakeContributor(
    override val order: Int,
    private val stableText: String? = null,
    private val dynamicText: String? = null,
) : PromptContributor {
    override suspend fun stable(purpose: TurnPurpose): String? = stableText
    override suspend fun dynamic(purpose: TurnPurpose, userText: String?): String? = dynamicText
}

class FakeAmbientSource(override val id: String, private val block: suspend () -> String?) : AmbientSource {
    override suspend fun snapshot(): String? = block()
}

class RecordingListener : TurnListener {
    val turns = mutableListOf<org.xmyun.starmate.core.model.CompletedTurn>()
    override suspend fun onTurnCompleted(turn: org.xmyun.starmate.core.model.CompletedTurn) {
        turns += turn
    }
}

/** 去掉完整的 <x>…</x>，流式时把未闭合的 <x> 之后的内容先藏起来。 */
class StripXProcessor : ReplyPostProcessor {
    override val order: Int = 10
    private val complete = Regex("<x>.*?</x>", RegexOption.DOT_MATCHES_ALL)

    override fun preview(rawSoFar: String): String {
        val stripped = complete.replace(rawSoFar, "")
        val open = stripped.indexOf("<x")
        return if (open >= 0) stripped.substring(0, open) else stripped
    }

    override suspend fun finish(
        rawText: String,
        reply: org.xmyun.starmate.core.model.ProcessedReply,
        purpose: TurnPurpose,
    ) = reply.copy(displayText = complete.replace(reply.displayText, "").trim())
}

/** 组装一个带全部假依赖的引擎。 */
class EngineHarness(
    client: LlmClient? = FakeLlmClient(),
    providers: Set<ToolProvider> = emptySet(),
    contributors: Set<PromptContributor> = emptySet(),
    ambientSources: Set<AmbientSource> = emptySet(),
    listeners: Set<TurnListener> = emptySet(),
    processors: Set<ReplyPostProcessor> = emptySet(),
    val broker: ScriptedBroker = ScriptedBroker(),
    val guidelines: FakeGuidelines = FakeGuidelines(),
) {
    val store = FakeMessageStore()
    val prefs = FakeCompanionPrefs()
    val ledger = FakeUsageLedger()
    val router = FakeModelRouter(client)
    val diag = FakeDiagLog()
    val clock = MutableClock()
    val companions = FakeCompanionStore()
    val consent = InMemoryToolConsentStore()
    val ambient = AmbientBlock(ambientSources, clock)
    val assembler = PromptAssembler(guidelines, companions, contributors, ambient, diag)
    val toolLoop = ToolLoop(providers, broker, consent, router, diag)
    val engine = DefaultConversationEngine(
        messages = store,
        prefs = prefs,
        ledger = ledger,
        router = router,
        diag = diag,
        guidelines = guidelines,
        assembler = assembler,
        toolLoop = toolLoop,
        listeners = listeners,
        postProcessors = processors,
        clock = clock,
    )
}
