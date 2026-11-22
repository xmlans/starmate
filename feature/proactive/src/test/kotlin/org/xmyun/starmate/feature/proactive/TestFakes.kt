package org.xmyun.starmate.feature.proactive

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.ConversationEngine
import org.xmyun.starmate.core.model.DailyUsage
import org.xmyun.starmate.core.model.DiagEntry
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.HeadlessResult
import org.xmyun.starmate.core.model.HeadlessTurn
import org.xmyun.starmate.core.model.MessageStore
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.QuietHours
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TokenUsage
import org.xmyun.starmate.core.model.TurnEvent
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UsageEntry
import org.xmyun.starmate.core.model.UsageLedger
import org.xmyun.starmate.core.model.UserInput
import org.xmyun.starmate.feature.proactive.model.Care
import org.xmyun.starmate.feature.proactive.model.CareStatus
import org.xmyun.starmate.feature.proactive.model.LogOutcome
import org.xmyun.starmate.feature.proactive.model.SendLogEntry
import org.xmyun.starmate.feature.proactive.notify.ProactiveNotifier
import org.xmyun.starmate.feature.proactive.platform.NetworkStatus
import org.xmyun.starmate.feature.proactive.platform.ProactiveTexts
import org.xmyun.starmate.feature.proactive.platform.RandomSource
import org.xmyun.starmate.feature.proactive.prefs.ProactivePrefs
import org.xmyun.starmate.feature.proactive.schedule.CareScheduler
import org.xmyun.starmate.feature.proactive.schedule.CheckInScheduler
import org.xmyun.starmate.feature.proactive.store.CareStore
import org.xmyun.starmate.feature.proactive.store.SendLogStore

val TEST_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

/** 本地时间（上海）转 Instant。 */
fun local(text: String): Instant = java.time.LocalDateTime.parse(text).atZone(TEST_ZONE).toInstant()

class MutableClock(var now: Instant, private val zone: ZoneId = TEST_ZONE) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now
}

/** 每次都返回同一个值（夹在区间内）。 */
class FixedRandom(var value: Long = 0) : RandomSource {
    override fun nextLong(from: Long, until: Long): Long = value.coerceIn(from, until - 1)
}

class FakeDiag : DiagLog {
    val lines = mutableListOf<String>()
    override fun log(tag: String, message: String) {
        lines += "$tag: $message"
    }
    override val entries: StateFlow<List<DiagEntry>> = MutableStateFlow(emptyList())
}

/**
 * 内存里的 Preferences DataStore。Windows 宿主上文件版 DataStore 第二次写入时偶尔改名失败
 * （目标文件仍被占用），与被测逻辑无关，测试里一律用内存版。
 */
class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock {
            val next = transform(state.value).toPreferences()
            state.value = next
            next
        }
}

fun newPrefs(): ProactivePrefs = ProactivePrefs(InMemoryPreferencesDataStore())

class FakeCompanionPrefs(
    clinginess: Clinginess = Clinginess.MEDIUM,
    quietHours: QuietHours = QuietHours.DEFAULT,
) : CompanionPrefs {
    val clinginessState = MutableStateFlow(clinginess)
    val quietState = MutableStateFlow(quietHours)

    override val adultConfirmed: Flow<Boolean> = flowOf(true)
    override suspend fun setAdultConfirmed(value: Boolean) = Unit
    override val onboardingState: Flow<OnboardingState> = flowOf(OnboardingState.DONE)
    override suspend fun setOnboardingState(state: OnboardingState) = Unit
    override val pendingOnboardingProfile: Flow<String?> = flowOf(null)
    override suspend fun setPendingOnboardingProfile(json: String?) = Unit
    override val quietHours: Flow<QuietHours> = quietState
    override suspend fun setQuietHours(value: QuietHours) {
        quietState.value = value
    }
    override val clinginess: Flow<Clinginess> = clinginessState
    override suspend fun setClinginess(value: Clinginess) {
        clinginessState.value = value
    }
    override fun dailyBudget(purpose: TurnPurpose): Flow<Int> = flowOf(20_000)
    override suspend fun setDailyBudget(purpose: TurnPurpose, tokens: Int) = Unit
    override val historyTokenBudget: Flow<Int> = flowOf(8000)
    override suspend fun setHistoryTokenBudget(tokens: Int) = Unit
}

class FakeMessageStore : MessageStore {
    val messages = mutableListOf<ChatMessage>()

    fun add(role: Role, at: Instant, purpose: TurnPurpose = TurnPurpose.CHAT) {
        messages += ChatMessage(
            id = (messages.size + 1).toLong(),
            role = role,
            rawText = "m",
            displayText = "m",
            purpose = purpose,
            createdAt = at,
        )
    }

    override fun observeRecent(limit: Int): Flow<List<ChatMessage>> = flowOf(messages.takeLast(limit))
    override suspend fun recent(limit: Int): List<ChatMessage> = messages.takeLast(limit)
    override suspend fun between(from: Instant, to: Instant): List<ChatMessage> =
        messages.filter { !it.createdAt.isBefore(from) && it.createdAt.isBefore(to) }
    override suspend fun byId(id: Long): ChatMessage? = messages.firstOrNull { it.id == id }
    override suspend fun insert(message: ChatMessage): Long {
        val id = (messages.size + 1).toLong()
        messages += message.copy(id = id)
        return id
    }
    override suspend fun update(message: ChatMessage) = Unit
    override suspend fun delete(id: Long) = Unit
    override suspend fun lastMessageAt(role: Role?): Instant? =
        messages.filter { role == null || it.role == role }.maxOfOrNull { it.createdAt }
}

class FakeLedger(var allowed: Boolean = true) : UsageLedger {
    override suspend fun record(entry: UsageEntry) = Unit
    override suspend fun usedToday(purpose: TurnPurpose): Int = 0
    override suspend fun allow(purpose: TurnPurpose): Boolean = allowed
    override fun observeDaily(days: Int): Flow<List<DailyUsage>> = flowOf(emptyList())
}

/** 记录调用顺序的共享事件表。 */
class EventLog {
    val events = mutableListOf<String>()
}

class FakeEngine(
    private val events: EventLog,
    private val messages: FakeMessageStore,
    private val clock: Clock,
) : ConversationEngine {
    var result: HeadlessResult = HeadlessResult.Success("""{"send": false}""", TokenUsage())
    var deliverError: Exception? = null
    var onRunHeadless: () -> Unit = {}
    val headlessCalls = mutableListOf<HeadlessTurn>()
    val delivered = mutableListOf<String>()
    override val isBusy = MutableStateFlow(false)

    override fun send(input: UserInput): Flow<TurnEvent> = emptyFlow()

    override suspend fun runHeadless(turn: HeadlessTurn): HeadlessResult {
        headlessCalls += turn
        events.events += "runHeadless"
        onRunHeadless()
        return result
    }

    override suspend fun deliver(rawText: String, purpose: TurnPurpose): ChatMessage {
        deliverError?.let { throw it }
        events.events += "deliver"
        delivered += rawText
        val message = ChatMessage(
            role = Role.ASSISTANT,
            rawText = rawText,
            displayText = rawText,
            purpose = purpose,
            createdAt = clock.instant(),
        )
        val id = messages.insert(message)
        return message.copy(id = id)
    }
}

class FakeNotifier(private val events: EventLog = EventLog(), var allowed: Boolean = true) : ProactiveNotifier {
    val shown = mutableListOf<ChatMessage>()
    var cancelled = 0
    override fun canNotify(): Boolean = allowed
    override suspend fun showMessage(message: ChatMessage) {
        events.events += "notify"
        shown += message
    }
    override fun cancelAll() {
        cancelled++
    }
}

/** 线程安全：设置观察的测试里会从后台线程调用。 */
class FakeScheduler : CareScheduler {
    val scheduled: MutableMap<Long, Instant> = java.util.concurrent.ConcurrentHashMap()
    val cancelled: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf())
    override fun schedule(careId: Long, at: Instant) {
        scheduled[careId] = at
    }
    override fun cancel(careId: Long) {
        cancelled += careId
        scheduled.remove(careId)
    }
}

class FakeCheckInScheduler : CheckInScheduler {
    @Volatile
    var enabled: Boolean? = null
    override fun enable() {
        enabled = true
    }
    override fun disable() {
        enabled = false
    }
}

class FakeNetwork(var online: Boolean = true) : NetworkStatus {
    override fun isOnline(): Boolean = online
}

class FakeTexts(override val chinese: Boolean = false) : ProactiveTexts {
    override fun chatGuide(): String = "GUIDE"
    override fun instructionTemplate(): String =
        "now={{now}}\nambient={{ambient}}\nsince={{since_last_chat}}\nsummary={{summary}}\ntrigger={{trigger}}\nRULES"
}

class InMemoryCareStore : CareStore {
    private val state = MutableStateFlow<List<Care>>(emptyList())
    private var nextId = 1L
    val all: List<Care> get() = state.value

    override suspend fun insert(care: Care): Long {
        val id = nextId++
        state.value = state.value + care.copy(id = id)
        return id
    }
    override suspend fun update(care: Care) {
        state.value = state.value.map { if (it.id == care.id) care else it }
    }
    override suspend fun byId(id: Long): Care? = state.value.firstOrNull { it.id == id }
    override suspend fun pending(): List<Care> =
        state.value.filter { it.status == CareStatus.PENDING }.sortedBy { it.scheduledFor }
    override fun observePending(): Flow<List<Care>> =
        state.map { list -> list.filter { it.status == CareStatus.PENDING } }
    override suspend fun countPending(): Int = state.value.count { it.status == CareStatus.PENDING }
}

class InMemorySendLogStore(private val events: EventLog = EventLog()) : SendLogStore {
    val entries: MutableList<SendLogEntry> = java.util.Collections.synchronizedList(mutableListOf())
    override suspend fun add(entry: SendLogEntry) {
        events.events += "log:${entry.outcome}"
        entries += entry
    }
    override fun observeRecent(limit: Int): Flow<List<SendLogEntry>> = flowOf(entries.reversed().take(limit))
    override suspend fun countSentSince(from: Instant): Int =
        entries.count { it.outcome == LogOutcome.SENT && !it.at.isBefore(from) }
    override suspend fun countSentAfter(after: Instant?): Int =
        entries.count { it.outcome == LogOutcome.SENT && (after == null || it.at.isAfter(after)) }
    override suspend fun lastSentAt(): Instant? =
        entries.filter { it.outcome == LogOutcome.SENT }.maxOfOrNull { it.at }
}
