package org.xmyun.starmate.feature.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.xmyun.starmate.core.model.AppState
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.ConversationEngine
import org.xmyun.starmate.core.model.DiagEntry
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.EmbeddingClient
import org.xmyun.starmate.core.model.EndpointConfig
import org.xmyun.starmate.core.model.HeadlessResult
import org.xmyun.starmate.core.model.HeadlessTurn
import org.xmyun.starmate.core.model.LlmClient
import org.xmyun.starmate.core.model.MessageStore
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ModelRouter
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.QuietHours
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TokenUsage
import org.xmyun.starmate.core.model.TurnEvent
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UserInput
import org.xmyun.starmate.feature.memory.consolidate.Archiver
import org.xmyun.starmate.feature.memory.consolidate.Consolidator
import org.xmyun.starmate.feature.memory.consolidate.MemoryLlm
import org.xmyun.starmate.feature.memory.consolidate.Reflector
import org.xmyun.starmate.feature.memory.consolidate.RenderSource
import org.xmyun.starmate.feature.memory.data.MemoryRepository
import org.xmyun.starmate.feature.memory.db.MemoryDatabase
import org.xmyun.starmate.feature.memory.db.MemoryDatabaseHolder
import org.xmyun.starmate.feature.memory.inject.MemoryPromptContributor
import org.xmyun.starmate.feature.memory.inject.MemoryRender
import org.xmyun.starmate.feature.memory.inject.SnapshotBuilder
import org.xmyun.starmate.feature.memory.model.MemorySettings
import org.xmyun.starmate.feature.memory.prompt.PromptSource
import org.xmyun.starmate.feature.memory.prompt.PromptTexts
import org.xmyun.starmate.feature.memory.retrieval.Recaller
import org.xmyun.starmate.feature.memory.retrieval.VectorIndex
import org.xmyun.starmate.feature.memory.tools.FragmentBudget
import org.xmyun.starmate.feature.memory.tools.MemoryToolProvider
import org.xmyun.starmate.feature.memory.tools.ReadEpisodeTool
import org.xmyun.starmate.feature.memory.tools.RecallMemoryTool
import org.xmyun.starmate.feature.memory.tools.RememberFragmentTool

val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 2026-10-29 12:00（星期四），与参考实现测试用的“现在”一致。 */
val NOW: Long = LocalDateTime.of(2026, 10, 29, 12, 0).atZone(ZONE).toInstant().toEpochMilli()

fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
    LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toInstant().toEpochMilli()

/** 测试直接读真实的提示词文件，保证用的就是要发给模型的文字。 */
object TestPrompts {
    val zh: PromptTexts by lazy { PromptTexts.parse(File("src/main/res/raw-zh/memory_prompts.txt").readText()) }
    val en: PromptTexts by lazy { PromptTexts.parse(File("src/main/res/raw/memory_prompts.txt").readText()) }
}

class FixedPromptSource(private val texts: PromptTexts = TestPrompts.zh) : PromptSource {
    override fun texts(): PromptTexts = texts
}

fun zhRender() = MemoryRender(TestPrompts.zh, ZONE)

class MutableClock(var now: Long, private val zone: ZoneId = ZONE) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = Instant.ofEpochMilli(now)
}

class FakeDiag : DiagLog {
    private val state = MutableStateFlow<List<DiagEntry>>(emptyList())
    override val entries: StateFlow<List<DiagEntry>> = state
    override fun log(tag: String, message: String) {
        state.value = state.value + DiagEntry(Instant.EPOCH, tag, message)
    }
    fun contains(text: String) = state.value.any { text in it.message }
}

/** 假的引擎：按调用顺序交给 responder 决定返回什么，并记下每次调用。 */
class FakeEngine(var responder: (HeadlessTurn) -> HeadlessResult = { ok("""{"ops":[],"gist":""}""") }) : ConversationEngine {
    val calls = mutableListOf<HeadlessTurn>()
    override val isBusy: StateFlow<Boolean> = MutableStateFlow(false)
    override fun send(input: UserInput): Flow<TurnEvent> = error("not used")
    override suspend fun runHeadless(turn: HeadlessTurn): HeadlessResult {
        calls += turn
        return responder(turn)
    }
    override suspend fun deliver(rawText: String, purpose: TurnPurpose): ChatMessage = error("not used")

    companion object {
        fun ok(text: String) = HeadlessResult.Success(text, TokenUsage())
    }
}

class FakeEmbedding(private val vectorFor: (String) -> FloatArray) : EmbeddingClient {
    override val modelId: String = "fake-embed"
    var calls = 0
    override suspend fun embed(texts: List<String>): List<FloatArray> {
        calls++
        return texts.map(vectorFor)
    }
}

class FakeRouter(var embeddingClient: EmbeddingClient? = null) : ModelRouter {
    override suspend fun chat(role: ModelRole): LlmClient? = null
    override suspend fun embedding(): EmbeddingClient? = embeddingClient
    override suspend fun endpoint(role: ModelRole): EndpointConfig? = null
    override suspend fun markToolsUnsupported(role: ModelRole) = Unit
}

class FakeMessageStore(val messages: MutableList<ChatMessage> = mutableListOf()) : MessageStore {
    override fun observeRecent(limit: Int): Flow<List<ChatMessage>> = flowOf(messages.takeLast(limit))
    override suspend fun recent(limit: Int): List<ChatMessage> = messages.takeLast(limit)
    override suspend fun between(from: Instant, to: Instant) = messages.filter { it.createdAt >= from && it.createdAt < to }
    override suspend fun byId(id: Long) = messages.firstOrNull { it.id == id }
    override suspend fun insert(message: ChatMessage): Long {
        val id = (messages.maxOfOrNull { it.id } ?: 0) + 1
        messages += message.copy(id = id)
        return id
    }
    override suspend fun update(message: ChatMessage) {
        messages.replaceAll { if (it.id == message.id) message else it }
    }
    override suspend fun delete(id: Long) {
        messages.removeAll { it.id == id }
    }
    override suspend fun lastMessageAt(role: Role?): Instant? = messages.lastOrNull()?.createdAt
}

class FakeCompanionPrefs : CompanionPrefs {
    override val adultConfirmed = MutableStateFlow(true)
    override suspend fun setAdultConfirmed(value: Boolean) { adultConfirmed.value = value }
    override val onboardingState = MutableStateFlow(OnboardingState.DONE)
    override suspend fun setOnboardingState(state: OnboardingState) { onboardingState.value = state }
    override val pendingOnboardingProfile = MutableStateFlow<String?>(null)
    override suspend fun setPendingOnboardingProfile(json: String?) { pendingOnboardingProfile.value = json }
    override val quietHours = MutableStateFlow(QuietHours.DEFAULT)
    override suspend fun setQuietHours(value: QuietHours) { quietHours.value = value }
    override val clinginess = MutableStateFlow(Clinginess.MEDIUM)
    override suspend fun setClinginess(value: Clinginess) { clinginess.value = value }
    private val budgets = MutableStateFlow(emptyMap<TurnPurpose, Int>())
    override fun dailyBudget(purpose: TurnPurpose): Flow<Int> = budgets.map { it[purpose] ?: 80000 }
    override suspend fun setDailyBudget(purpose: TurnPurpose, tokens: Int) { budgets.value = budgets.value + (purpose to tokens) }
    override val historyTokenBudget = MutableStateFlow(8000)
    override suspend fun setHistoryTokenBudget(tokens: Int) { historyTokenBudget.value = tokens }
}

class FakeAppState : AppState {
    override val isForeground = MutableStateFlow(true)
}

fun appContext(): Context = ApplicationProvider.getApplicationContext()

/** 测试用文件数据库，带随机后缀，放在 Robolectric 每个测试独立的数据库目录里。 */
fun newHolder(): MemoryDatabaseHolder {
    val context = appContext()
    val name = "memory-${UUID.randomUUID()}.db"
    return MemoryDatabaseHolder(context.getDatabasePath(name)) { MemoryDatabase.build(context, name) }
}

/** 把整个记忆模块按生产代码的方式手工装起来。 */
class MemoryGraph(
    val engine: FakeEngine = FakeEngine(),
    val router: FakeRouter = FakeRouter(),
    val clock: MutableClock = MutableClock(NOW),
    val settings: MemorySettings = MemorySettings(),
    val messages: FakeMessageStore = FakeMessageStore(),
    val prefs: FakeCompanionPrefs = FakeCompanionPrefs(),
) {
    val diag = FakeDiag()
    val holder = newHolder()
    val repo = MemoryRepository(holder)
    val prompts = FixedPromptSource()
    val renders = RenderSource(prompts, clock)
    val vectors = VectorIndex(router, repo, diag)
    val recaller = Recaller(repo, vectors, settings)
    val llm = MemoryLlm(engine)
    val archiver = Archiver(repo, llm, renders, settings, diag)
    val reflector = Reflector(repo, llm, renders, vectors, settings, diag)
    val consolidator = Consolidator(repo, llm, recaller, vectors, archiver, reflector, renders, settings, clock, diag)
    val snapshots = SnapshotBuilder(repo, renders, settings, clock)
    val contributor = MemoryPromptContributor(repo, snapshots, recaller, renders, settings, clock, messages, prefs)
    val budget = FragmentBudget()
    val rememberTool = RememberFragmentTool(repo, renders, budget, settings, clock)
    val recallTool = RecallMemoryTool(repo, recaller, renders, settings, clock)
    val readTool = ReadEpisodeTool(repo, renders, settings)
    val toolProvider = MemoryToolProvider(rememberTool, recallTool, readTool, budget)
    val writer = DefaultMemoryWriter(repo, clock)
    val daySummary = DefaultDaySummarySource(repo, consolidator, diag)

    fun close() = holder.close()
}

fun chatMessage(id: Long, role: Role, text: String, at: Long, purpose: TurnPurpose = TurnPurpose.CHAT) = ChatMessage(
    id = id,
    role = role,
    rawText = text,
    displayText = text,
    purpose = purpose,
    createdAt = Instant.ofEpochMilli(at),
)
