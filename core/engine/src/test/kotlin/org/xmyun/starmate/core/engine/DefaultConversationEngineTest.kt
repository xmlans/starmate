@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.xmyun.starmate.core.engine

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.core.model.ATTACHMENT_PREVIEW_KEY
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.AttachmentTypes
import org.xmyun.starmate.core.model.HeadlessResult
import org.xmyun.starmate.core.model.HeadlessTurn
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.LlmEvent
import org.xmyun.starmate.core.model.LlmException
import org.xmyun.starmate.core.model.MessageStatus
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.StopReason
import org.xmyun.starmate.core.model.ToolCall
import org.xmyun.starmate.core.model.TurnEvent
import org.xmyun.starmate.core.model.TurnListener
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UserInput

class DefaultConversationEngineTest {

    @Test
    fun normalTurnEmitsStartedTextsCompletedAndStoresBothMessages() = runTest {
        val h = EngineHarness(client = FakeLlmClient { _, _ -> textReply("你", "好呀") })
        val events = h.engine.send(UserInput.Text("嗨")).toList()

        assertTrue(events.first() is TurnEvent.Started)
        assertEquals(
            listOf("你", "你好呀"),
            events.filterIsInstance<TurnEvent.Text>().map { it.displaySoFar },
        )
        assertTrue(events.last() is TurnEvent.Completed)
        assertEquals(4, events.size)

        val all = h.store.all
        assertEquals(2, all.size)
        assertEquals(Role.USER, all[0].role)
        assertEquals("嗨", all[0].rawText)
        assertEquals(Role.ASSISTANT, all[1].role)
        assertEquals(MessageStatus.COMPLETE, all[1].status)
        assertEquals("你好呀", all[1].rawText)
        val started = events.first() as TurnEvent.Started
        assertEquals(all[0].id, started.userMessageId)
        assertEquals(all[1].id, started.assistantMessageId)
        assertFalse(h.engine.isBusy.value)
    }

    @Test
    fun postProcessorStripsTagsFromDisplayButNotRaw() = runTest {
        val h = EngineHarness(
            client = FakeLlmClient { _, _ -> textReply("今天<x>秘", "密</x>真好") },
            processors = setOf(StripXProcessor()),
        )
        val events = h.engine.send(UserInput.Text("嗨")).toList()

        // 流式过程中未闭合的标签被藏起来
        assertEquals(
            listOf("今天", "今天真好"),
            events.filterIsInstance<TurnEvent.Text>().map { it.displaySoFar },
        )
        val done = (events.last() as TurnEvent.Completed).message
        assertEquals("今天真好", done.displayText)
        assertEquals("今天<x>秘密</x>真好", done.rawText)
        assertEquals(done, h.store.byId(done.id))
    }

    @Test
    fun turnListenerReceivesTurnMatchingStore() = runTest {
        val listener = RecordingListener()
        val h = EngineHarness(listeners = setOf(listener))
        h.engine.send(UserInput.Text("嗨")).toList()

        val turn = listener.turns.single()
        assertEquals(TurnPurpose.CHAT, turn.purpose)
        assertEquals(h.store.byId(turn.userMessage!!.id), turn.userMessage)
        assertEquals(h.store.byId(turn.assistantMessage.id), turn.assistantMessage)
        assertEquals(MessageStatus.COMPLETE, turn.assistantMessage.status)
    }

    @Test
    fun throwingListenerAndProcessorDoNotBreakTurn() = runTest {
        val bad = object : TurnListener {
            override suspend fun onTurnCompleted(turn: org.xmyun.starmate.core.model.CompletedTurn) = error("boom")
        }
        val badProcessor = object : org.xmyun.starmate.core.model.ReplyPostProcessor {
            override val order = 1
            override fun preview(rawSoFar: String): String = error("preview boom")
            override suspend fun finish(
                rawText: String,
                reply: org.xmyun.starmate.core.model.ProcessedReply,
                purpose: TurnPurpose,
            ): org.xmyun.starmate.core.model.ProcessedReply = error("finish boom")
        }
        val h = EngineHarness(listeners = setOf(bad), processors = setOf(badProcessor))
        val events = h.engine.send(UserInput.Text("嗨")).toList()
        assertTrue(events.last() is TurnEvent.Completed)
        assertEquals("好", (events.last() as TurnEvent.Completed).message.displayText)
        assertTrue(h.diag.entries.value.any { it.message.contains("TurnListener") })
        assertTrue(h.diag.entries.value.any { it.message.contains("ReplyPostProcessor") })
    }

    @Test
    fun kickoffDoesNotCreateUserMessage() = runTest {
        val client = FakeLlmClient { _, _ -> textReply("你好，我是小星") }
        val h = EngineHarness(client = client)
        val events = h.engine.send(UserInput.Kickoff(TurnPurpose.ONBOARDING)).toList()

        assertNull((events.first() as TurnEvent.Started).userMessageId)
        val all = h.store.all
        assertEquals(1, all.size)
        assertEquals(Role.ASSISTANT, all.single().role)
        assertEquals(TurnPurpose.ONBOARDING, all.single().purpose)
        val sent = client.requests.single().messages.single()
        assertEquals(Role.USER, sent.role)
        assertTrue(sent.text.startsWith("现在是 "))
        assertEquals(TurnPurpose.ONBOARDING, h.ledger.records.single().purpose)
    }

    @Test
    fun attachmentPreviewPrefixesRawTextOnly() = runTest {
        val client = FakeLlmClient()
        val h = EngineHarness(client = client)
        val quote = Attachment(
            AttachmentTypes.QUOTE,
            JsonObject(
                mapOf(
                    "title" to JsonPrimitive("雨天"),
                    ATTACHMENT_PREVIEW_KEY to JsonPrimitive("引用她的日记《雨天》：今天下雨了"),
                ),
            ),
        )
        val noPreview = Attachment("other", JsonObject(emptyMap()))
        h.engine.send(UserInput.Text("你那天心情不好吗", listOf(quote, noPreview))).toList()

        val user = h.store.all.first { it.role == Role.USER }
        assertTrue(user.rawText.startsWith("引用她的日记《雨天》：今天下雨了"))
        assertTrue(user.rawText.endsWith("你那天心情不好吗"))
        assertEquals("引用她的日记《雨天》：今天下雨了\n你那天心情不好吗", user.rawText)
        assertEquals("你那天心情不好吗", user.displayText)
        assertEquals(2, user.attachments.size)
        assertTrue(client.requests.single().messages.last().text.startsWith("引用她的日记《雨天》"))
    }

    @Test
    fun invalidKeyFailsAndKeepsUserMessage() = runTest {
        val h = EngineHarness(
            client = FakeLlmClient { _, _ -> flow { throw LlmException(LlmErrorKind.INVALID_KEY, "401", 401) } },
        )
        val events = h.engine.send(UserInput.Text("嗨")).toList()

        val failed = events.last() as TurnEvent.Failed
        assertEquals(LlmErrorKind.INVALID_KEY, failed.error.kind)
        val assistant = h.store.byId(failed.assistantMessageId)!!
        assertEquals(MessageStatus.FAILED, assistant.status)
        assertEquals(LlmErrorKind.INVALID_KEY, assistant.errorKind)
        assertEquals(1, h.store.all.count { it.role == Role.USER })
        assertFalse(h.engine.isBusy.value)
    }

    @Test
    fun notConfiguredFails() = runTest {
        val h = EngineHarness(client = null)
        val events = h.engine.send(UserInput.Text("嗨")).toList()
        val failed = events.last() as TurnEvent.Failed
        assertEquals(LlmErrorKind.NOT_CONFIGURED, failed.error.kind)
        assertEquals(LlmErrorKind.NOT_CONFIGURED, h.store.byId(failed.assistantMessageId)!!.errorKind)
        assertEquals(listOf(ModelRole.CHAT), h.router.requestedRoles)
    }

    @Test
    fun retryReplacesFailedAssistantWithoutDuplicatingUserMessage() = runTest {
        var fail = true
        val client = FakeLlmClient { _, _ ->
            if (fail) flow { throw LlmException(LlmErrorKind.TIMEOUT, "timeout") } else textReply("这次好了")
        }
        val h = EngineHarness(client = client)
        val failed = h.engine.send(UserInput.Text("嗨")).toList().last() as TurnEvent.Failed
        fail = false
        val events = h.engine.send(UserInput.Retry(failed.assistantMessageId)).toList()

        assertTrue(events.last() is TurnEvent.Completed)
        val all = h.store.all
        assertEquals(2, all.size)
        assertEquals(Role.USER, all[0].role)
        assertEquals(Role.ASSISTANT, all[1].role)
        assertEquals(MessageStatus.COMPLETE, all[1].status)
        assertEquals("这次好了", all[1].rawText)
        assertNull(h.store.byId(failed.assistantMessageId))
        assertEquals(all[0].id, (events.first() as TurnEvent.Started).userMessageId)
        // 重跑时发给模型的仍是那条用户消息
        assertTrue(client.requests.last().messages.last().text.startsWith("嗨"))
    }

    @Test
    fun retryOfFailedKickoffRunsKickoffAgain() = runTest {
        var fail = true
        val h = EngineHarness(
            client = FakeLlmClient { _, _ ->
                if (fail) flow { throw LlmException(LlmErrorKind.UNREACHABLE, "down") } else textReply("你好")
            },
        )
        val failed = h.engine.send(UserInput.Kickoff(TurnPurpose.ONBOARDING)).toList().last() as TurnEvent.Failed
        fail = false
        val events = h.engine.send(UserInput.Retry(failed.assistantMessageId)).toList()
        assertTrue(events.last() is TurnEvent.Completed)
        assertEquals(1, h.store.all.size)
        assertEquals(TurnPurpose.ONBOARDING, h.store.all.single().purpose)
    }

    @Test
    fun secondSendWhileBusyFailsImmediately() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = EngineHarness(
            client = FakeLlmClient { _, _ ->
                flow {
                    emit(LlmEvent.TextDelta("嗯"))
                    gate.await()
                    emit(LlmEvent.Completed(DEFAULT_USAGE, StopReason.END))
                }
            },
        )
        val first = backgroundScope.launch { h.engine.send(UserInput.Text("一")).toList() }
        runCurrent()
        assertTrue(h.engine.isBusy.value)

        h.engine.send(UserInput.Text("二")).test {
            val event = awaitItem()
            assertTrue(event is TurnEvent.Failed)
            assertEquals(DefaultConversationEngine.NO_MESSAGE_ID, (event as TurnEvent.Failed).assistantMessageId)
            assertEquals(LlmErrorKind.UNKNOWN, event.error.kind)
            awaitComplete()
        }
        // 第二次没有存任何消息
        assertEquals(1, h.store.all.count { it.role == Role.USER })

        gate.complete(Unit)
        first.join()
        assertFalse(h.engine.isBusy.value)
        assertEquals(MessageStatus.COMPLETE, h.store.all.last().status)
    }

    @Test
    fun cancelledTurnMarksAssistantFailedAndReleasesLock() = runTest {
        val never = CompletableDeferred<Unit>()
        var block = true
        val h = EngineHarness(
            client = FakeLlmClient { _, _ ->
                if (block) {
                    flow {
                        emit(LlmEvent.TextDelta("说到一半"))
                        never.await()
                    }
                } else {
                    textReply("好")
                }
            },
        )
        val job = launch { h.engine.send(UserInput.Text("嗨")).collect {} }
        runCurrent()
        job.cancelAndJoin()

        val assistant = h.store.all.single { it.role == Role.ASSISTANT }
        assertEquals(MessageStatus.FAILED, assistant.status)
        assertEquals("说到一半", assistant.rawText)
        assertFalse(h.engine.isBusy.value)

        block = false
        val events = h.engine.send(UserInput.Retry(assistant.id)).toList()
        assertTrue(events.last() is TurnEvent.Completed)
    }

    @Test
    fun onboardingStateMakesTextPurposeOnboarding() = runTest {
        val h = EngineHarness()
        h.prefs.onboardingState.value = OnboardingState.INTERVIEWING
        h.engine.send(UserInput.Text("我叫阿远")).toList()
        assertTrue(h.store.all.all { it.purpose == TurnPurpose.ONBOARDING })
    }

    @Test
    fun historyIsSentBeforeCurrentMessage() = runTest {
        val client = FakeLlmClient { _, index -> textReply("回复$index") }
        val h = EngineHarness(client = client)
        h.engine.send(UserInput.Text("第一句")).toList()
        h.engine.send(UserInput.Text("第二句")).toList()
        val sent = client.requests[1].messages
        assertEquals(listOf("第一句", "回复0"), sent.dropLast(1).map { it.text })
        assertTrue(sent.last().text.startsWith("第二句"))
    }

    @Test
    fun runHeadlessBudgetExceededDoesNotCallModel() = runTest {
        val client = FakeLlmClient()
        val h = EngineHarness(client = client)
        h.ledger.blocked += TurnPurpose.MEMORY
        val result = h.engine.runHeadless(HeadlessTurn(TurnPurpose.MEMORY, "整理一下"))
        assertEquals(HeadlessResult.BudgetExceeded, result)
        assertTrue(client.requests.isEmpty())
        assertTrue(h.ledger.records.isEmpty())
    }

    @Test
    fun runHeadlessBackgroundHasNoToolsAndUsesPersona() = runTest {
        val client = FakeLlmClient { _, _ -> textReply("今天和阿远聊了很多。") }
        val provider = FakeToolProvider(FakeTool("weather_get"))
        val h = EngineHarness(client = client, providers = setOf(provider))
        h.engine.send(UserInput.Text("嗨")).toList()

        val result = h.engine.runHeadless(
            HeadlessTurn(TurnPurpose.DIARY, "写日记", includeRecentMessages = 12, jsonOutput = true, maxOutputTokens = 800),
        )
        assertEquals("今天和阿远聊了很多。", (result as HeadlessResult.Success).text)
        val request = client.requests.last()
        assertTrue(request.tools.isEmpty())
        assertTrue(request.jsonOutput)
        assertEquals(800, request.maxOutputTokens)
        assertTrue(request.system.stable.contains("你叫小星。"))
        assertEquals(listOf("嗨", "今天和阿远聊了很多。", "写日记"), request.messages.map { it.text })
        // 不存消息
        assertEquals(2, h.store.all.size)
        assertEquals(ModelRole.BACKGROUND, h.router.requestedRoles.last())
    }

    @Test
    fun runHeadlessSystemOverrideExcludesPersona() = runTest {
        val client = FakeLlmClient { _, _ -> textReply("{}") }
        val listener = RecordingListener()
        val h = EngineHarness(client = client, listeners = setOf(listener))
        val result = h.engine.runHeadless(
            HeadlessTurn(TurnPurpose.MEMORY, "原话……", systemOverride = "你是记忆整理器", includeRecentMessages = 5),
        )
        assertTrue(result is HeadlessResult.Success)
        val request = client.requests.single()
        assertEquals("你是记忆整理器", request.system.stable)
        assertFalse(request.system.stable.contains("小星"))
        assertEquals(1, request.messages.size)
        assertTrue(listener.turns.isEmpty())
        assertTrue(h.store.all.isEmpty())
    }

    @Test
    fun runHeadlessConvertsLlmExceptionToFailed() = runTest {
        val h = EngineHarness(
            client = FakeLlmClient { _, _ -> flow { throw LlmException(LlmErrorKind.QUOTA_OR_RATE_LIMIT, "429", 429) } },
        )
        val result = h.engine.runHeadless(HeadlessTurn(TurnPurpose.PROACTIVE, "要不要找他"))
        assertEquals(LlmErrorKind.QUOTA_OR_RATE_LIMIT, (result as HeadlessResult.Failed).error.kind)
    }

    @Test
    fun deliverStoresMessageAndNotifiesListeners() = runTest {
        val listener = RecordingListener()
        val h = EngineHarness(listeners = setOf(listener), processors = setOf(StripXProcessor()))
        val stored = h.engine.deliver("早呀<x>tag</x>", TurnPurpose.PROACTIVE)

        assertEquals(stored, h.store.byId(stored.id))
        assertEquals(MessageStatus.COMPLETE, stored.status)
        assertEquals("早呀", stored.displayText)
        assertEquals("早呀<x>tag</x>", stored.rawText)
        val turn = listener.turns.single()
        assertNull(turn.userMessage)
        assertEquals(stored, turn.assistantMessage)
        assertEquals(TurnPurpose.PROACTIVE, turn.purpose)
    }

    @Test
    fun ledgerGetsOneRecordPerModelCallWithCorrectPurpose() = runTest {
        val tool = FakeTool("weather_get")
        val client = FakeLlmClient { request, _ ->
            if (request.messages.any { it.role == Role.TOOL }) {
                textReply("下雨了")
            } else {
                toolCallReply(ToolCall("c1", "weather_get", JsonObject(emptyMap())))
            }
        }
        val h = EngineHarness(client = client, providers = setOf(FakeToolProvider(tool)))
        val events = h.engine.send(UserInput.Text("天气呢")).toList()

        assertEquals(2, client.requests.size)
        assertEquals(2, h.ledger.records.size)
        assertTrue(h.ledger.records.all { it.purpose == TurnPurpose.CHAT && it.model == "fake-model" })
        assertEquals(10, h.ledger.records.first().inputTokens)
        assertTrue(events.any { it is TurnEvent.ToolStarted })
        assertTrue(events.any { it is TurnEvent.ToolFinished })

        h.engine.runHeadless(HeadlessTurn(TurnPurpose.DIARY, "写日记"))
        assertEquals(TurnPurpose.DIARY, h.ledger.records.last().purpose)
        assertEquals(3, h.ledger.records.size)
    }

    @Test
    fun blankReplyFailsAsBadResponse() = runTest {
        val h = EngineHarness(client = FakeLlmClient { _, _ -> textReply() })
        val failed = h.engine.send(UserInput.Text("嗨")).toList().last() as TurnEvent.Failed
        assertEquals(LlmErrorKind.BAD_RESPONSE, failed.error.kind)
    }

    @Test
    fun listenerMayCallRunHeadlessWithoutDeadlock() = runTest {
        lateinit var h: EngineHarness
        var headless: HeadlessResult? = null
        val listener = object : TurnListener {
            override suspend fun onTurnCompleted(turn: org.xmyun.starmate.core.model.CompletedTurn) {
                headless = h.engine.runHeadless(HeadlessTurn(TurnPurpose.ONBOARDING, "抽取", role = ModelRole.CHAT, systemOverride = "抽取器"))
            }
        }
        h = EngineHarness(listeners = setOf(listener))
        h.engine.send(UserInput.Text("嗨")).first { it is TurnEvent.Completed }
        assertTrue(headless is HeadlessResult.Success)
    }
}
