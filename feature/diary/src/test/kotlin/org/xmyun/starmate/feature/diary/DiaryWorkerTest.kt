package org.xmyun.starmate.feature.diary

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Optional
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.ConversationEngine
import org.xmyun.starmate.core.model.DaySummary
import org.xmyun.starmate.core.model.DaySummarySource
import org.xmyun.starmate.core.model.DiagEntry
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.HeadlessResult
import org.xmyun.starmate.core.model.HeadlessTurn
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.LlmException
import org.xmyun.starmate.core.model.MemoryCategory
import org.xmyun.starmate.core.model.MemoryWriter
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.TokenUsage
import org.xmyun.starmate.core.model.TurnEvent
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UserInput
import org.xmyun.starmate.feature.diary.db.DiaryDatabase
import org.xmyun.starmate.feature.diary.db.DiaryDatabaseHolder
import org.xmyun.starmate.feature.diary.db.DiaryEntryEntity
import org.xmyun.starmate.feature.diary.prefs.DiaryPrefs
import org.xmyun.starmate.feature.diary.write.DiaryFrequency
import org.xmyun.starmate.feature.diary.write.DiaryPrompt
import org.xmyun.starmate.feature.diary.write.DiaryPromptSource
import org.xmyun.starmate.feature.diary.write.DiaryRunResult
import org.xmyun.starmate.feature.diary.write.DiaryWriter

private class FixedClock(private val now: Instant, private val zone: ZoneId) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = FixedClock(now, zone)
    override fun instant(): Instant = now
}

private class FakeEngine(var responder: (HeadlessTurn) -> HeadlessResult) : ConversationEngine {
    val calls = mutableListOf<HeadlessTurn>()
    override val isBusy: StateFlow<Boolean> = MutableStateFlow(false)
    override fun send(input: UserInput): Flow<TurnEvent> = error("not used")
    override suspend fun runHeadless(turn: HeadlessTurn): HeadlessResult {
        calls += turn
        return responder(turn)
    }
    override suspend fun deliver(rawText: String, purpose: TurnPurpose): ChatMessage = error("not used")
}

private class FakeSummary(var summary: DaySummary?) : DaySummarySource {
    val calls = mutableListOf<Triple<Instant, Instant, Boolean>>()
    override suspend fun daySummary(from: Instant, to: Instant, refresh: Boolean): DaySummary? {
        calls += Triple(from, to, refresh)
        return summary
    }
}

private class FakeMemoryWriter : MemoryWriter {
    val added = mutableListOf<Triple<String, String, Int>>()
    override suspend fun add(text: String, category: String, importance: Int) {
        added += Triple(text, category, importance)
    }
}

private class FakeDiag : DiagLog {
    override val entries: StateFlow<List<DiagEntry>> = MutableStateFlow(emptyList())
    val messages = mutableListOf<String>()
    override fun log(tag: String, message: String) {
        messages += message
    }
}

@RunWith(RobolectricTestRunner::class)
class DiaryWorkerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 11, 12, 23, 40).atZone(zone).toInstant()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "diary-${UUID.randomUUID()}.db"
    private val holder = DiaryDatabaseHolder(context.getDatabasePath(dbName)) { DiaryDatabase.build(context, dbName) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = DiaryPrefs(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File.createTempFile("diary", ".preferences_pb").also { it.delete() } }))
    private val summary = FakeSummary(DaySummary(turnCount = 8, gist = "小明聊到面试通过，很开心", newMemories = listOf("小明面试通过了")))
    private val memory = FakeMemoryWriter()
    private val diag = FakeDiag()
    private val engine = FakeEngine { HeadlessResult.Success(GOOD, TokenUsage()) }
    private val prompts = object : DiaryPromptSource {
        override fun prompt() = DiaryPrompt.parse(File("src/main/res/raw-zh/diary_prompt.txt").readText())
    }

    private fun writer(source: DaySummarySource? = summary) = DiaryWriter(
        Optional.ofNullable(source), Optional.of(memory), engine, holder, prefs, prompts, FixedClock(now, zone), diag,
    ).also { it.random = { 0.1 } }

    @After
    fun tearDown() {
        holder.close()
        scope.cancel()
    }

    @Test
    fun writesEntryAndFeedsSummaryBackToMemory() = runTest {
        holder.dao().insert(DiaryEntryEntity(date = "2026-11-10", title = "前天", mood = "", body = "第一段。\n上一篇的结尾。", summary = "", createdAt = 0, read = true))
        val result = writer().run()
        assertTrue(result is DiaryRunResult.Written)
        val call = engine.calls.single()
        assertEquals(TurnPurpose.DIARY, call.purpose)
        assertEquals(ModelRole.CHAT, call.role)
        assertEquals(null, call.systemOverride)
        assertTrue(call.jsonOutput)
        assertTrue(call.instruction.contains("小明聊到面试通过，很开心"))
        assertTrue(call.instruction.contains("小明面试通过了"))
        assertTrue(call.instruction.contains("上一篇的结尾。"))
        assertTrue(call.instruction.contains("150–400"))
        // 当天从 04:00 起。先不整理地看一眼（决定写不写），确定要写了才要求整理
        assertEquals(listOf(false, true), summary.calls.map { it.third })
        val (from, to, refresh) = summary.calls.last()
        assertEquals(LocalDateTime.of(2026, 11, 12, 4, 0).atZone(zone).toInstant(), from)
        assertEquals(now, to)
        assertTrue(refresh)
        val entry = holder.dao().byDate("2026-11-12")!!
        assertEquals("面试通过的晚上", entry.title)
        assertFalse(entry.read)
        assertEquals(listOf(Triple("我替小明面试通过开心了一整晚", MemoryCategory.BETWEEN_US, 3)), memory.added)
        // 同一天不再写第二篇
        assertEquals(DiaryRunResult.AlreadyWritten, writer().run())
    }

    @Test
    fun backgroundModelWhenChosenInSettings() = runTest {
        prefs.setRole(ModelRole.BACKGROUND)
        writer().run()
        assertEquals(ModelRole.BACKGROUND, engine.calls.single().role)
    }

    @Test
    fun modelDeclines() = runTest {
        engine.responder = { HeadlessResult.Success("{\"write\": false}", TokenUsage()) }
        assertEquals(DiaryRunResult.ModelDeclined, writer().run())
        assertEquals(0, holder.dao().count())
        assertTrue(memory.added.isEmpty())
    }

    @Test
    fun budgetExceededWritesNothing() = runTest {
        engine.responder = { HeadlessResult.BudgetExceeded }
        assertEquals(DiaryRunResult.Budget, writer().run())
        assertEquals(0, holder.dao().count())
        assertTrue(diag.messages.any { "budget" in it })
    }

    @Test
    fun failureAndInvalidJsonWriteNothing() = runTest {
        engine.responder = { HeadlessResult.Failed(LlmException(LlmErrorKind.UNREACHABLE, "offline")) }
        assertEquals(DiaryRunResult.Failed, writer().run())
        engine.responder = { HeadlessResult.Success("今天不想用 JSON 写", TokenUsage()) }
        assertEquals(DiaryRunResult.Invalid, writer().run())
        assertEquals(0, holder.dao().count())
    }

    @Test
    fun nullSummaryNeverCallsTheModel() = runTest {
        summary.summary = null
        assertEquals(DiaryRunResult.NoConversation, writer().run())
        assertEquals(DiaryRunResult.NoSource, writer(source = null).run())
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun drawAndFrequencyDecideBeforeCallingModel() = runTest {
        summary.summary = DaySummary(turnCount = 5, gist = "", newMemories = emptyList())
        assertEquals(DiaryRunResult.NotChosen, writer().run())
        summary.summary = DaySummary(turnCount = 8, gist = "", newMemories = emptyList())
        prefs.setFrequency(DiaryFrequency.OFF)
        assertEquals(DiaryRunResult.NotChosen, writer().run())
        assertTrue(engine.calls.isEmpty())
        // 没抽中、频率关闭时都不能触发记忆整理（那一步要花用户的 token）
        assertTrue(summary.calls.none { it.third })
        // 立即写一篇：跳过抽签和频率，仍要求当天有对话
        assertTrue(writer().run(force = true) is DiaryRunResult.Written)
        assertTrue(engine.calls.single().instruction.contains("（没有记下梗概）"))
    }

    companion object {
        private val GOOD = """
            {"write": true, "title": "面试通过的晚上", "mood": "开心",
             "body": "今天他告诉我面试过了。我比他还紧张，听到消息的那一刻，心里像放了一颗糖。",
             "summary": "我替小明面试通过开心了一整晚"}
        """.trimIndent()
    }
}
