package org.xmyun.starmate.feature.proactive.work

import java.time.Duration
import java.util.Optional
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xmyun.starmate.core.model.AmbientSource
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.DaySummary
import org.xmyun.starmate.core.model.DaySummarySource
import org.xmyun.starmate.core.model.HeadlessResult
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.LlmException
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TokenUsage
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.proactive.EventLog
import org.xmyun.starmate.feature.proactive.FakeCheckInScheduler
import org.xmyun.starmate.feature.proactive.FakeCompanionPrefs
import org.xmyun.starmate.feature.proactive.FakeDiag
import org.xmyun.starmate.feature.proactive.FakeEngine
import org.xmyun.starmate.feature.proactive.FakeLedger
import org.xmyun.starmate.feature.proactive.FakeMessageStore
import org.xmyun.starmate.feature.proactive.FakeNetwork
import org.xmyun.starmate.feature.proactive.FakeNotifier
import org.xmyun.starmate.feature.proactive.FakeScheduler
import org.xmyun.starmate.feature.proactive.FakeTexts
import org.xmyun.starmate.feature.proactive.FixedRandom
import org.xmyun.starmate.feature.proactive.InMemoryCareStore
import org.xmyun.starmate.feature.proactive.InMemorySendLogStore
import org.xmyun.starmate.feature.proactive.MutableClock
import org.xmyun.starmate.feature.proactive.local
import org.xmyun.starmate.feature.proactive.model.Care
import org.xmyun.starmate.feature.proactive.model.CareStatus
import org.xmyun.starmate.feature.proactive.model.LogOutcome
import org.xmyun.starmate.feature.proactive.model.LogReasons
import org.xmyun.starmate.feature.proactive.model.LogSource
import org.xmyun.starmate.feature.proactive.model.SendLogEntry
import org.xmyun.starmate.feature.proactive.newPrefs
import org.xmyun.starmate.feature.proactive.prefs.ProactivePrefs
import org.xmyun.starmate.feature.proactive.schedule.CareRescheduler

class ProactiveRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = local("2026-11-12T15:00")
    private val clock = MutableClock(now)
    private val events = EventLog()
    private val messages = FakeMessageStore()
    private val engine = FakeEngine(events, messages, clock)
    private val ledger = FakeLedger()
    private val notifier = FakeNotifier(events)
    private val scheduler = FakeScheduler()
    private val network = FakeNetwork()
    private val companionPrefs = FakeCompanionPrefs(clinginess = Clinginess.MEDIUM)
    private val cares = InMemoryCareStore()
    private val logs = InMemorySendLogStore(events)
    private val diag = FakeDiag()
    private val prefsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: ProactivePrefs
    private var ambient: Set<AmbientSource> = emptySet()
    private var summary: Optional<DaySummarySource> = Optional.empty()

    @Before
    fun setUp() {
        prefs = newPrefs()
        // 上次聊天在 6 小时前，用户最后说话也在那时
        messages.add(Role.USER, now.minus(Duration.ofHours(6)))
    }

    @After
    fun tearDown() {
        prefsScope.cancel()
    }

    private fun runner(): ProactiveRunner {
        val rescheduler = CareRescheduler(
            cares, logs, scheduler, FakeCheckInScheduler(), prefs, companionPrefs, FixedRandom(0), clock, diag,
        )
        return ProactiveRunner(
            prefs = prefs,
            companionPrefs = companionPrefs,
            messages = messages,
            ledger = ledger,
            engine = engine,
            cares = cares,
            logs = logs,
            scheduler = scheduler,
            rescheduler = rescheduler,
            notifier = notifier,
            network = network,
            ambientSources = ambient,
            daySummary = summary,
            texts = FakeTexts(),
            random = FixedRandom(0),
            clock = clock,
            diag = diag,
        )
    }

    private suspend fun pendingCare(scheduledFor: java.time.Instant = now): Long = cares.insert(
        Care(
            topic = "job interview",
            reason = "ask how the interview went",
            scheduledFor = scheduledFor,
            nextAttemptAt = scheduledFor,
            createdAt = now.minus(Duration.ofDays(1)),
            updatedAt = now.minus(Duration.ofDays(1)),
        ),
    )

    private fun success(text: String) = HeadlessResult.Success(text, TokenUsage(100, 20))

    @Test
    fun gateDenied_neverCallsEngine_andDefersCare() = runTest {
        network.online = false
        val id = pendingCare()

        val outcome = runner().runCare(id)

        assertEquals(RunOutcome.BLOCKED, outcome)
        assertTrue(engine.headlessCalls.isEmpty())
        assertTrue(engine.delivered.isEmpty())
        val entry = logs.entries.single()
        assertEquals(LogOutcome.BLOCKED, entry.outcome)
        assertEquals("NO_NETWORK", entry.reason)
        val care = cares.byId(id)!!
        assertEquals(CareStatus.PENDING, care.status)
        assertEquals(now.plus(Duration.ofHours(1)), care.nextAttemptAt)
        assertEquals(now.plus(Duration.ofHours(1)), scheduler.scheduled[id])
    }

    @Test
    fun quietHours_checkInNeverCallsEngine() = runTest {
        clock.now = local("2026-11-12T23:30")

        val outcome = runner().runCheckIn()

        assertEquals(RunOutcome.BLOCKED, outcome)
        assertTrue(engine.headlessCalls.isEmpty())
        assertEquals("QUIET_HOURS", logs.entries.single().reason)
    }

    @Test
    fun masterSwitchOff_carePausedSilently() = runTest {
        prefs.setEnabled(false)
        val id = pendingCare()
        val before = cares.byId(id)!!

        assertEquals(RunOutcome.PAUSED, runner().runCare(id))
        runner().runCheckIn()

        // 不调用引擎、不登记下一次闹钟、不写记录，约定原样保持待发
        assertTrue(engine.headlessCalls.isEmpty())
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(logs.entries.isEmpty())
        assertEquals(before, cares.byId(id))
    }

    @Test
    fun clinginessOff_carePausedSilently_evenWhenOverdue() = runTest {
        companionPrefs.clinginessState.value = Clinginess.OFF
        val id = pendingCare(scheduledFor = now.minus(Duration.ofDays(4)))

        assertEquals(RunOutcome.PAUSED, runner().runCare(id))

        assertTrue(engine.headlessCalls.isEmpty())
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(logs.entries.isEmpty())
        assertEquals(CareStatus.PENDING, cares.byId(id)!!.status)
    }

    @Test
    fun budgetExceeded_doesNotSend() = runTest {
        engine.result = HeadlessResult.BudgetExceeded
        val id = pendingCare()

        val outcome = runner().runCare(id)

        assertEquals(RunOutcome.BLOCKED, outcome)
        assertTrue(engine.delivered.isEmpty())
        assertTrue(notifier.shown.isEmpty())
        assertEquals(LogOutcome.BLOCKED, logs.entries.single().outcome)
        assertEquals("BUDGET", logs.entries.single().reason)
        assertEquals(CareStatus.PENDING, cares.byId(id)!!.status)
        assertEquals(now.plus(Duration.ofHours(1)), scheduler.scheduled[id])
    }

    @Test
    fun ledgerDenied_isBlockedBeforeEngine() = runTest {
        ledger.allowed = false
        val id = pendingCare()

        runner().runCare(id)

        assertTrue(engine.headlessCalls.isEmpty())
        assertEquals("BUDGET", logs.entries.single().reason)
    }

    @Test
    fun sendFalse_doesNotSend_butMarksCareHandled() = runTest {
        engine.result = success("""{"send": false}""")
        val id = pendingCare()

        val outcome = runner().runCare(id)

        assertEquals(RunOutcome.DECLINED, outcome)
        assertTrue(engine.delivered.isEmpty())
        assertTrue(notifier.shown.isEmpty())
        val care = cares.byId(id)!!
        assertEquals(CareStatus.DECLINED, care.status)
        assertEquals(1, care.attempts)
        assertEquals(LogOutcome.DECLINED, logs.entries.single().outcome)
        assertEquals(LogReasons.MODEL_DECLINED, logs.entries.single().reason)
    }

    @Test
    fun unparseableOutput_isTreatedAsNotSending() = runTest {
        engine.result = success("hey there!")
        val id = pendingCare()

        runner().runCare(id)

        assertTrue(engine.delivered.isEmpty())
        assertEquals(LogReasons.UNPARSEABLE, logs.entries.single().reason)
        assertEquals(CareStatus.DECLINED, cares.byId(id)!!.status)
    }

    @Test
    fun normalPath_deliversThenLogsThenNotifies() = runTest {
        engine.result = success("""{"send": true, "text": "How did the interview go?"}""")
        val id = pendingCare()

        val outcome = runner().runCare(id)

        assertEquals(RunOutcome.SENT, outcome)
        assertEquals(listOf("runHeadless", "deliver", "log:SENT", "notify"), events.events)
        assertEquals(listOf("How did the interview go?"), engine.delivered)
        assertEquals(CareStatus.SENT, cares.byId(id)!!.status)
        val entry = logs.entries.single()
        assertEquals(LogOutcome.SENT, entry.outcome)
        assertEquals("How did the interview go?", entry.text)
        assertEquals(id, entry.careId)
        assertEquals("How did the interview go?", notifier.shown.single().displayText)

        val turn = engine.headlessCalls.single()
        assertEquals(TurnPurpose.PROACTIVE, turn.purpose)
        assertNull(turn.systemOverride)
        assertEquals(12, turn.includeRecentMessages)
        assertTrue(turn.jsonOutput)
        assertTrue(turn.instruction.contains("job interview"))
        assertTrue(turn.instruction.contains("ask how the interview went"))
        assertTrue(turn.instruction.contains("since=6 h 0 min"))
        assertTrue(turn.instruction.contains("Thursday, November 12, 2026, 15:00"))
        assertTrue(turn.instruction.contains("RULES"))
    }

    @Test
    fun checkIn_normalPath() = runTest {
        engine.result = success("""{"send": true, "text": "Hi!"}""")

        val outcome = runner().runCheckIn()

        assertEquals(RunOutcome.SENT, outcome)
        assertEquals(LogSource.CHECK_IN, logs.entries.single().source)
        assertTrue(engine.headlessCalls.single().instruction.contains("casual check-in"))
    }

    @Test
    fun checkIn_notIdleEnough_doesNotCallEngine() = runTest {
        messages.add(Role.ASSISTANT, now.minus(Duration.ofHours(5)))

        val outcome = runner().runCheckIn()

        assertEquals(RunOutcome.BLOCKED, outcome)
        assertTrue(engine.headlessCalls.isEmpty())
        assertEquals(LogReasons.NOT_IDLE, logs.entries.single().reason)
    }

    @Test
    fun checkIn_highClinginess_afterFourHours_callsEngine() = runTest {
        companionPrefs.clinginessState.value = Clinginess.HIGH
        messages.add(Role.ASSISTANT, now.minus(Duration.ofHours(4)))

        runner().runCheckIn()

        assertEquals(1, engine.headlessCalls.size)
    }

    @Test
    fun modelFailure_logsAndRetriesCareInOneHour() = runTest {
        engine.result = HeadlessResult.Failed(LlmException(LlmErrorKind.TIMEOUT, "timeout"))
        val id = pendingCare()

        val outcome = runner().runCare(id)

        assertEquals(RunOutcome.FAILED, outcome)
        assertEquals(LogOutcome.FAILED, logs.entries.single().outcome)
        assertEquals("TIMEOUT", logs.entries.single().reason)
        assertEquals(now.plus(Duration.ofHours(1)), scheduler.scheduled[id])
    }

    @Test
    fun deliverFailure_isLoggedAndNothingNotified() = runTest {
        engine.result = success("""{"send": true, "text": "Hi"}""")
        engine.deliverError = IllegalStateException("db")
        val id = pendingCare()

        val outcome = runner().runCare(id)

        assertEquals(RunOutcome.FAILED, outcome)
        assertTrue(notifier.shown.isEmpty())
        assertEquals(LogReasons.DELIVER_ERROR, logs.entries.single().reason)
        assertEquals(CareStatus.PENDING, cares.byId(id)!!.status)
    }

    @Test
    fun userStartsChattingDuringGeneration_dropsMessage() = runTest {
        engine.result = success("""{"send": true, "text": "Hi"}""")
        engine.onRunHeadless = { messages.add(Role.USER, now.plusSeconds(5)) }
        val id = pendingCare()

        runner().runCare(id)

        assertTrue(engine.delivered.isEmpty())
        assertEquals(LogReasons.CHAT_STARTED, logs.entries.single().reason)
        assertEquals(CareStatus.PENDING, cares.byId(id)!!.status)
    }

    @Test
    fun expiredCare_givesUpWithoutCallingEngine() = runTest {
        val id = pendingCare(scheduledFor = now.minus(Duration.ofDays(3)))

        val outcome = runner().runCare(id)

        assertEquals(RunOutcome.GAVE_UP, outcome)
        assertTrue(engine.headlessCalls.isEmpty())
        assertEquals(CareStatus.EXPIRED, cares.byId(id)!!.status)
        assertEquals(LogOutcome.GAVE_UP, logs.entries.single().outcome)
    }

    @Test
    fun alreadyHandledCare_isIgnored() = runTest {
        val id = pendingCare()
        cares.update(cares.byId(id)!!.copy(status = CareStatus.CANCELLED))

        assertEquals(RunOutcome.IGNORED, runner().runCare(id))
        assertTrue(engine.headlessCalls.isEmpty())
        assertTrue(logs.entries.isEmpty())
    }

    @Test
    fun twoUnansweredProactiveMessages_pauseUntilUserReplies() = runTest {
        engine.result = success("""{"send": false}""")
        // 用户最后说话在 11-12 09:00，之后她已发出两条，且都不是今天、距今超过 6 小时
        for (at in listOf(local("2026-11-12T10:00"), local("2026-11-12T17:00"))) {
            logs.entries += SendLogEntry(at = at, source = LogSource.CHECK_IN, outcome = LogOutcome.SENT)
        }
        clock.now = local("2026-11-13T12:00")

        runner().runCheckIn()
        assertTrue(engine.headlessCalls.isEmpty())
        assertEquals("UNANSWERED", logs.entries.last().reason)

        // 用户开口后恢复
        messages.add(Role.USER, local("2026-11-13T05:00"))
        runner().runCheckIn()
        assertEquals(1, engine.headlessCalls.size)
    }

    @Test
    fun ambientAndSummary_areWrittenIntoInstruction_slowSourcesIgnored() = runTest {
        ambient = setOf(
            object : AmbientSource {
                override val id = "a_weather"
                override suspend fun snapshot() = "Sunny, 18°C"
            },
            object : AmbientSource {
                override val id = "b_slow"
                override suspend fun snapshot(): String {
                    delay(5_000)
                    return "too slow"
                }
            },
            object : AmbientSource {
                override val id = "c_broken"
                override suspend fun snapshot(): String = error("boom")
            },
        )
        summary = Optional.of(
            object : DaySummarySource {
                override suspend fun daySummary(from: java.time.Instant, to: java.time.Instant, refresh: Boolean): DaySummary? {
                    assertFalse(refresh)
                    return DaySummary(turnCount = 3, gist = "talked about the interview", newMemories = emptyList())
                }
            },
        )
        engine.result = success("""{"send": false}""")

        runner().runCheckIn()

        val instruction = engine.headlessCalls.single().instruction
        assertTrue(instruction.contains("ambient=Sunny, 18°C"))
        assertFalse(instruction.contains("too slow"))
        assertTrue(instruction.contains("summary=talked about the interview"))
    }
}
