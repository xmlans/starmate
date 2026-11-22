package org.xmyun.starmate.feature.chat.chat

import app.cash.turbine.test
import java.time.Clock
import java.time.Instant
import java.util.Optional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.xmyun.starmate.core.model.MemoryWriter
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.ConfirmDecision
import org.xmyun.starmate.core.model.ConfirmRequest
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.LlmException
import org.xmyun.starmate.core.model.MessageStatus
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.RoleAssignment
import org.xmyun.starmate.core.model.TurnEvent
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UserInput
import org.xmyun.starmate.feature.chat.FakeBroker
import org.xmyun.starmate.feature.chat.FakeChatPrefs
import org.xmyun.starmate.feature.chat.FakeCompanionPrefs
import org.xmyun.starmate.feature.chat.FakeCompanionStore
import org.xmyun.starmate.feature.chat.FakeDiag
import org.xmyun.starmate.feature.chat.FakeDraftBus
import org.xmyun.starmate.feature.chat.FakeEngine
import org.xmyun.starmate.feature.chat.FakeMessageStore
import org.xmyun.starmate.feature.chat.FakeProviderStore
import org.xmyun.starmate.feature.chat.FakeTexts
import org.xmyun.starmate.feature.chat.common.ChatBackgroundScope
import org.xmyun.starmate.feature.chat.common.ChatRoutes
import org.xmyun.starmate.feature.chat.interview.InterviewCoordinator
import org.xmyun.starmate.feature.chat.interview.ProfileApplier

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = FakeMessageStore()
    private val engine = FakeEngine()
    private val broker = FakeBroker()
    private val draftBus = FakeDraftBus()
    private val companions = FakeCompanionStore()
    private val prefs = FakeCompanionPrefs(state = OnboardingState.DONE, adult = true)
    private val providers = FakeProviderStore().apply {
        assignmentFlow.value = mapOf(ModelRole.CHAT to RoleAssignment(ModelRole.CHAT, "openai", "gpt-a"))
    }
    private val chatPrefs = FakeChatPrefs().apply { flow.value = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(memoryWriter: Optional<MemoryWriter> = Optional.empty()): ChatViewModel {
        val texts = FakeTexts()
        val coordinator = InterviewCoordinator(
            prefs = prefs,
            messages = store,
            engine = { engine },
            applier = ProfileApplier(companions, prefs, Optional.empty(), texts, FakeDiag()),
            texts = texts,
            background = ChatBackgroundScope(backgroundScope),
            clock = Clock.systemUTC(),
            diag = FakeDiag(),
        )
        return ChatViewModel(
            messages = store,
            engine = engine,
            broker = broker,
            draftBus = draftBus,
            companions = companions,
            prefs = prefs,
            providers = providers,
            coordinator = coordinator,
            chatPrefs = chatPrefs,
            renderers = emptySet(),
            inputActions = emptySet(),
            invites = emptySet(),
            memoryWriter = memoryWriter,
        )
    }

    @Test
    fun reportDeletesTheReplyAndAsksMemoryToForgetIt() = runTest(dispatcher) {
        val forgotten = mutableListOf<ChatMessage>()
        val writer = object : MemoryWriter {
            override suspend fun add(text: String, category: String, importance: Int) = Unit
            override suspend fun forgetReply(message: ChatMessage) {
                forgotten += message
            }
        }
        val id = store.insert(
            ChatMessage(
                role = Role.ASSISTANT,
                rawText = "一段冒犯性的回复",
                displayText = "一段冒犯性的回复",
                purpose = TurnPurpose.CHAT,
                createdAt = Instant.EPOCH,
            ),
        )
        val vm = viewModel(Optional.of(writer))
        advanceUntilIdle()

        vm.report(store.byId(id)!!)
        advanceUntilIdle()

        // 从聊天里删掉，之后也不会作为历史发给模型
        assertNull(store.byId(id))
        assertEquals(listOf(id), forgotten.map { it.id })
    }

    private fun placeholder(purpose: TurnPurpose = TurnPurpose.CHAT) = ChatMessage(
        role = Role.ASSISTANT,
        rawText = "",
        displayText = "",
        purpose = purpose,
        createdAt = Instant.EPOCH,
        status = MessageStatus.STREAMING,
    )

    @Test
    fun streamingTextUpdatesStepByStep() = runTest(dispatcher) {
        val pipe = Channel<TurnEvent>(Channel.UNLIMITED)
        var assistantId = 0L
        engine.script = { input ->
            flow {
                val userId = store.addUser((input as UserInput.Text).text, TurnPurpose.CHAT)
                assistantId = store.insert(placeholder())
                emit(TurnEvent.Started(userId, assistantId))
                for (event in pipe) emit(event)
            }
        }
        val vm = viewModel()
        advanceUntilIdle()

        vm.onInputChange("你好")
        vm.send()
        advanceUntilIdle()
        assertTrue(vm.sending.value)
        assertEquals("", vm.input.value)
        assertTrue("还没有文字时显示正在输入", vm.typing.value)

        pipe.send(TurnEvent.Text("你"))
        advanceUntilIdle()
        assertEquals("你", vm.items.value.single { it.id == assistantId }.text)
        assertTrue(vm.items.value.single { it.id == assistantId }.streaming)
        assertFalse(vm.typing.value)

        pipe.send(TurnEvent.Text("你好呀"))
        advanceUntilIdle()
        assertEquals("你好呀", vm.items.value.single { it.id == assistantId }.text)

        pipe.send(TurnEvent.ToolStarted("c1", "weather_get", "查天气"))
        advanceUntilIdle()
        assertTrue(vm.items.value.single { it.id == assistantId }.tools.single().running)
        pipe.send(TurnEvent.ToolFinished("c1", isError = false))
        advanceUntilIdle()
        assertFalse(vm.items.value.single { it.id == assistantId }.tools.single().running)

        val done = store.byId(assistantId)!!.copy(rawText = "你好呀，今天怎么样", displayText = "你好呀，今天怎么样", status = MessageStatus.COMPLETE)
        store.update(done)
        pipe.send(TurnEvent.Completed(done))
        pipe.close()
        advanceUntilIdle()

        assertFalse(vm.sending.value)
        val item = vm.items.value.single { it.id == assistantId }
        assertEquals("你好呀，今天怎么样", item.text)
        assertFalse(item.streaming)
        assertFalse(item.failed)
        assertEquals(listOf("你好"), vm.items.value.filter { it.isUser }.map { it.text })
    }

    @Test
    fun failureShowsRetryAndRetrySucceeds() = runTest(dispatcher) {
        engine.script = { input ->
            when (input) {
                is UserInput.Text -> flow {
                    val userId = store.addUser(input.text, TurnPurpose.CHAT)
                    val id = store.insert(placeholder())
                    emit(TurnEvent.Started(userId, id))
                    store.update(store.byId(id)!!.copy(status = MessageStatus.FAILED, errorKind = LlmErrorKind.INVALID_KEY))
                    emit(TurnEvent.Failed(id, LlmException(LlmErrorKind.INVALID_KEY, "bad key")))
                }
                is UserInput.Retry -> flow {
                    store.delete(input.failedAssistantMessageId)
                    val id = store.insert(placeholder())
                    emit(TurnEvent.Started(null, id))
                    emit(TurnEvent.Text("好的"))
                    val done = store.byId(id)!!.copy(rawText = "好的", displayText = "好的", status = MessageStatus.COMPLETE)
                    store.update(done)
                    emit(TurnEvent.Completed(done))
                }
                else -> flow { }
            }
        }
        val vm = viewModel()
        advanceUntilIdle()

        vm.onInputChange("在吗")
        vm.send()
        advanceUntilIdle()

        val failed = vm.items.value.last()
        assertTrue(failed.failed)
        assertEquals(LlmErrorKind.INVALID_KEY, failed.errorKind)
        assertEquals(ChatRoutes.SETTINGS_MODELS, org.xmyun.starmate.feature.chat.common.ErrorTexts.fixRoute(failed.errorKind))
        // 用户消息照常保留
        assertEquals("在吗", vm.items.value.first { it.isUser }.text)

        vm.retry(failed.id)
        advanceUntilIdle()

        assertEquals(UserInput.Retry(failed.id), engine.sent.last())
        val last = vm.items.value.last()
        assertEquals("好的", last.text)
        assertFalse(last.failed)
        assertFalse(vm.items.value.any { it.failed })
    }

    @Test
    fun failureWithoutMessageGivesLightNotice() = runTest(dispatcher) {
        engine.script = { flow { emit(TurnEvent.Failed(0L, LlmException(LlmErrorKind.UNKNOWN, "busy"))) } }
        val vm = viewModel()
        advanceUntilIdle()
        vm.events.test {
            vm.onInputChange("hi")
            vm.send()
            advanceUntilIdle()
            assertEquals(ChatEvent.Busy, awaitItem())
        }
        assertFalse(vm.items.value.any { it.failed })
    }

    @Test
    fun approvingConfirmCardResolvesBroker() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val request = ConfirmRequest(id = "r1", toolName = "calendar_create_event", summary = "明天 9 点加一个日程", allowRemember = true)
        broker.pendingFlow.value = request
        assertEquals(request, vm.pendingConfirm.value)

        vm.approve(request, remember = false)
        assertEquals(listOf("r1" to ConfirmDecision(approved = true, remember = false)), broker.resolved)

        val second = request.copy(id = "r2")
        vm.approve(second, remember = true)
        assertEquals("r2" to ConfirmDecision(approved = true, remember = true), broker.resolved.last())

        val third = request.copy(id = "r3", allowRemember = false)
        vm.approve(third, remember = true)
        assertEquals("r3" to ConfirmDecision(approved = true, remember = false), broker.resolved.last())

        vm.deny(request.copy(id = "r4"))
        assertEquals("r4" to ConfirmDecision(approved = false), broker.resolved.last())
    }

    @Test
    fun interviewingWithoutMessagesKicksOff() = runTest(dispatcher) {
        prefs.stateFlow.value = OnboardingState.INTERVIEWING
        viewModel()
        advanceUntilIdle()
        assertEquals(listOf<UserInput>(UserInput.Kickoff(TurnPurpose.ONBOARDING)), engine.sent)
    }

    @Test
    fun interviewAlreadyStartedDoesNotKickOff() = runTest(dispatcher) {
        prefs.stateFlow.value = OnboardingState.INTERVIEWING
        store.addAssistant("你好呀", TurnPurpose.ONBOARDING)
        viewModel()
        advanceUntilIdle()
        assertTrue(engine.sent.isEmpty())
    }

    @Test
    fun gateSendsToOnboarding() = runTest(dispatcher) {
        prefs.adultFlow.value = false
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(ChatGate.NeedsOnboarding(ChatRoutes.ONBOARDING_WELCOME), vm.gate.value)

        prefs.adultFlow.value = true
        providers.assignmentFlow.value = emptyMap()
        advanceUntilIdle()
        assertEquals(ChatGate.NeedsOnboarding(ChatRoutes.ONBOARDING_MODEL), vm.gate.value)

        providers.assignmentFlow.value = mapOf(ModelRole.CHAT to RoleAssignment(ModelRole.CHAT, "openai", "gpt-a"))
        prefs.stateFlow.value = OnboardingState.NOT_STARTED
        advanceUntilIdle()
        assertEquals(ChatGate.NeedsOnboarding(ChatRoutes.ONBOARDING_COMPANION), vm.gate.value)

        prefs.stateFlow.value = OnboardingState.DONE
        advanceUntilIdle()
        assertEquals(ChatGate.Ready, vm.gate.value)
        assertTrue(engine.sent.isEmpty())
    }

    @Test
    fun draftAttachmentIsSentWithText() = runTest(dispatcher) {
        val quote = org.xmyun.starmate.core.model.Attachment(
            type = org.xmyun.starmate.core.model.AttachmentTypes.QUOTE,
            payload = kotlinx.serialization.json.buildJsonObject { put("preview", kotlinx.serialization.json.JsonPrimitive("日记：下雨天")) },
        )
        draftBus.offer(quote)
        val vm = viewModel()
        advanceUntilIdle()
        vm.onEnter()
        assertEquals(quote, vm.draft.value)
        vm.onInputChange("想起来了")
        vm.send()
        advanceUntilIdle()
        assertEquals(UserInput.Text("想起来了", listOf(quote)), engine.sent.single())
        assertEquals(null, vm.draft.value)
    }
}
