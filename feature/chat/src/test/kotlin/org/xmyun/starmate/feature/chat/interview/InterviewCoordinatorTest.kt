package org.xmyun.starmate.feature.chat.interview

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.core.model.AttachmentTypes
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.CompletedTurn
import org.xmyun.starmate.core.model.HeadlessResult
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.LlmException
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TokenUsage
import org.xmyun.starmate.core.model.ToolCall
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.chat.FakeCompanionPrefs
import org.xmyun.starmate.feature.chat.FakeCompanionStore
import org.xmyun.starmate.feature.chat.FakeDiag
import org.xmyun.starmate.feature.chat.FakeEngine
import org.xmyun.starmate.feature.chat.FakeMemoryWriter
import org.xmyun.starmate.feature.chat.FakeMessageStore
import org.xmyun.starmate.feature.chat.FakeTexts
import org.xmyun.starmate.feature.chat.common.ChatBackgroundScope

class InterviewCoordinatorTest {

    private val prefs = FakeCompanionPrefs(state = OnboardingState.INTERVIEWING, adult = true)
    private val companions = FakeCompanionStore()
    private val messages = FakeMessageStore()
    private val engine = FakeEngine()
    private val memory = FakeMemoryWriter()
    private val texts = FakeTexts()
    private val clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC)

    private fun TestScope.coordinator() = InterviewCoordinator(
        prefs = prefs,
        messages = messages,
        engine = { engine },
        applier = ProfileApplier(companions, prefs, Optional.of(memory), texts, FakeDiag()),
        texts = texts,
        background = ChatBackgroundScope(this),
        clock = clock,
        diag = FakeDiag(),
    )

    private fun summaryMessages(): List<ChatMessage> =
        messages.all.value.filter { msg -> msg.attachments.any { it.type == AttachmentTypes.ONBOARDING_SUMMARY } }

    @Test
    fun finishToolCall_setsDoneThenPending_insertsSummary_noMemoryYet() = runTest {
        val coordinator = coordinator()
        val tool = FinishOnboardingTool(coordinator, texts)
        val args = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"nickname":"阿青","likes":["猫"],"proactivity":"often"}""",
        ) as JsonObject

        val result = tool.execute(args, ToolContext(TurnPurpose.ONBOARDING))

        assertFalse(result.isError)
        assertEquals(OnboardingState.DONE, prefs.stateFlow.value)
        val stateIndex = prefs.calls.indexOf("state:DONE")
        val pendingIndex = prefs.calls.indexOf("pending:set")
        assertTrue("状态应先于暂存写入", stateIndex in 0 until pendingIndex)
        assertNotNull(prefs.pendingFlow.value)
        assertEquals("阿青", OnboardingProfile.parse(prefs.pendingFlow.value).nickname)

        val summary = summaryMessages().single()
        assertEquals(Role.ASSISTANT, summary.role)
        assertEquals("", summary.rawText)
        assertEquals(1, summary.attachments.size)
        assertFalse(InterviewCoordinator.isConfirmed(summary.attachments.single()))

        assertTrue("确认前不写记忆", memory.entries.isEmpty())
        assertEquals("", companions.flow.value.userNickname)
    }

    @Test
    fun twelveAnswersTriggerExtraction_withTranscript() = runTest {
        val coordinator = coordinator()
        messages.addAssistant("你好呀，我是小星", TurnPurpose.ONBOARDING)
        repeat(12) { i ->
            messages.addUser("回答$i", TurnPurpose.ONBOARDING)
            messages.addAssistant("回应$i", TurnPurpose.ONBOARDING)
        }
        engine.headlessResult = HeadlessResult.Success("""{"nickname":"阿青","sleepTime":"01:00","wakeTime":"09:00"}""", TokenUsage())
        val lastUser = messages.all.value.last { it.role == Role.USER }
        val lastAssistant = messages.all.value.last()

        coordinator.onTurnCompleted(CompletedTurn(TurnPurpose.ONBOARDING, lastUser, lastAssistant, emptyList()))

        val call = engine.headless.single()
        assertEquals(TurnPurpose.ONBOARDING, call.purpose)
        assertEquals(ModelRole.CHAT, call.role)
        assertEquals("EXTRACT PROMPT", call.systemOverride)
        assertTrue(call.jsonOutput)
        assertTrue(call.instruction.contains("回答0"))
        assertTrue(call.instruction.contains("回答11"))
        assertTrue(call.instruction.contains("你好呀，我是小星"))

        assertEquals(OnboardingState.DONE, prefs.stateFlow.value)
        assertEquals("阿青", OnboardingProfile.parse(prefs.pendingFlow.value).nickname)
        assertEquals(1, summaryMessages().size)
    }

    @Test
    fun elevenAnswersDoNotTriggerExtraction() = runTest {
        val coordinator = coordinator()
        repeat(11) { messages.addUser("回答$it", TurnPurpose.ONBOARDING) }
        val last = messages.all.value.last()
        coordinator.onTurnCompleted(CompletedTurn(TurnPurpose.ONBOARDING, last, last, emptyList()))
        assertTrue(engine.headless.isEmpty())
        assertEquals(OnboardingState.INTERVIEWING, prefs.stateFlow.value)
    }

    @Test
    fun failedAutoExtractionKeepsInterviewOpenThenRetries() = runTest {
        val coordinator = coordinator()
        repeat(12) { messages.addUser("回答$it", TurnPurpose.ONBOARDING) }
        engine.headlessResult = HeadlessResult.Failed(LlmException(LlmErrorKind.UNREACHABLE, "offline"))
        fun turn(): CompletedTurn {
            val last = messages.all.value.last()
            return CompletedTurn(TurnPurpose.ONBOARDING, last, last, emptyList())
        }

        // 第 12 条回答后请求失败：访谈不结束，也不留下空的小结
        coordinator.onTurnCompleted(turn())
        assertEquals(1, engine.headless.size)
        assertEquals(OnboardingState.INTERVIEWING, prefs.stateFlow.value)
        assertTrue(summaryMessages().isEmpty())

        // 下一轮网络恢复：重试成功，资料没有丢
        messages.addUser("回答12", TurnPurpose.ONBOARDING)
        engine.headlessResult = HeadlessResult.Success("""{"nickname":"阿青"}""", TokenUsage())
        coordinator.onTurnCompleted(turn())
        assertEquals(2, engine.headless.size)
        assertEquals(OnboardingState.DONE, prefs.stateFlow.value)
        assertEquals("阿青", OnboardingProfile.parse(prefs.pendingFlow.value).nickname)
    }

    @Test
    fun autoExtractionGivesUpAfterRetryTurns() = runTest {
        val coordinator = coordinator()
        val answers = InterviewCoordinator.EXTRACT_AFTER_ANSWERS + InterviewCoordinator.EXTRACT_RETRY_TURNS
        repeat(answers) { messages.addUser("回答$it", TurnPurpose.ONBOARDING) }
        engine.headlessResult = HeadlessResult.Failed(LlmException(LlmErrorKind.UNREACHABLE, "offline"))
        val last = messages.all.value.last()

        coordinator.onTurnCompleted(CompletedTurn(TurnPurpose.ONBOARDING, last, last, emptyList()))

        // 多试了几轮还是失败：以空资料结束，不让访谈永远停不下来
        assertEquals(OnboardingState.DONE, prefs.stateFlow.value)
        assertTrue(OnboardingProfile.parse(prefs.pendingFlow.value).isEmpty())
    }

    @Test
    fun extractionFailureFinishesWithEmptyProfile() = runTest {
        val coordinator = coordinator()
        messages.addUser("我叫阿青", TurnPurpose.ONBOARDING)
        engine.headlessResult = HeadlessResult.Success("抱歉我不会", TokenUsage())
        coordinator.skip()
        assertEquals(1, engine.headless.size)
        assertEquals(OnboardingState.DONE, prefs.stateFlow.value)
        assertTrue(OnboardingProfile.parse(prefs.pendingFlow.value).isEmpty())
    }

    @Test
    fun skipWithoutAnswers_doesNotCallEngine() = runTest {
        val coordinator = coordinator()
        messages.addAssistant("你好呀，我是小星，想怎么称呼你？", TurnPurpose.ONBOARDING)
        coordinator.skip()
        assertTrue(engine.headless.isEmpty())
        assertEquals(OnboardingState.DONE, prefs.stateFlow.value)
        assertEquals(1, summaryMessages().size)
    }

    @Test
    fun skipWithAnswers_extracts() = runTest {
        val coordinator = coordinator()
        messages.addUser("叫我阿青就好", TurnPurpose.ONBOARDING)
        engine.headlessResult = HeadlessResult.Success("""{"nickname":"阿青"}""", TokenUsage())
        coordinator.skip()
        assertEquals(1, engine.headless.size)
        assertTrue(engine.headless.single().instruction.contains("叫我阿青就好"))
    }

    @Test
    fun messageWhilePending_commitsAndClears() = runTest {
        val coordinator = coordinator()
        coordinator.finish(OnboardingProfile(nickname = "阿青", likes = listOf("猫"), sleepTime = "01:00", wakeTime = "09:00"))
        assertNotNull(prefs.pendingFlow.value)

        // 访谈已结束，用户接着聊的这一轮用途是 CHAT
        val userId = messages.addUser("今天好累", TurnPurpose.CHAT)
        val assistantId = messages.addAssistant("辛苦啦", TurnPurpose.CHAT)
        coordinator.onTurnCompleted(
            CompletedTurn(TurnPurpose.CHAT, messages.byId(userId), messages.byId(assistantId)!!, emptyList()),
        )

        assertNull(prefs.pendingFlow.value)
        assertEquals("阿青", companions.flow.value.userNickname)
        assertEquals(3, memory.entries.size) // 称呼、作息、喜欢
        assertTrue(InterviewCoordinator.isConfirmed(summaryMessages().single().attachments.single()))
    }

    @Test
    fun finishingTurnItselfDoesNotCommit() = runTest {
        val coordinator = coordinator()
        val userId = messages.addUser("差不多就这些", TurnPurpose.ONBOARDING)
        coordinator.finish(OnboardingProfile(nickname = "阿青"))
        val assistantId = messages.addAssistant("好呀，我记住啦", TurnPurpose.ONBOARDING)
        coordinator.onTurnCompleted(
            CompletedTurn(
                TurnPurpose.ONBOARDING,
                messages.byId(userId),
                messages.byId(assistantId)!!,
                listOf(ToolCall("c1", FinishOnboardingTool.NAME, JsonObject(emptyMap()))),
            ),
        )
        assertNotNull(prefs.pendingFlow.value)
        assertTrue(memory.entries.isEmpty())
    }

    @Test
    fun editsChangePendingAndConfirmUsesEdited() = runTest {
        val coordinator = coordinator()
        coordinator.finish(OnboardingProfile(nickname = "阿青", likes = listOf("猫", "火锅")))
        val pending = OnboardingProfile.parse(prefs.pendingFlow.value)
        val items = SummaryItems.of(pending)
        val edited = SummaryItems.remove(SummaryItems.edit(pending, items.first { it.field == SummaryField.NICKNAME }, "青青"), items.first { it.value == "火锅" })
        coordinator.updatePending(edited)
        coordinator.confirm()
        assertEquals("青青", companions.flow.value.userNickname)
        assertEquals(2, memory.entries.size)
        assertFalse(memory.entries.any { it.text.contains("火锅") })
    }

    @Test
    fun secondFinishIsIgnored() = runTest {
        val coordinator = coordinator()
        coordinator.finish(OnboardingProfile(nickname = "阿青"))
        coordinator.finish(OnboardingProfile(nickname = "别人"))
        assertEquals(1, summaryMessages().size)
        assertEquals("阿青", OnboardingProfile.parse(prefs.pendingFlow.value).nickname)
    }
}
