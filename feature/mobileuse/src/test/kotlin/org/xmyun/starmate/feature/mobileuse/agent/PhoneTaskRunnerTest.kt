package org.xmyun.starmate.feature.mobileuse.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.TokenUsage
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.mobileuse.FakeDevice
import org.xmyun.starmate.feature.mobileuse.FakeLedger
import org.xmyun.starmate.feature.mobileuse.FakeLlmClient
import org.xmyun.starmate.feature.mobileuse.FakeLogger
import org.xmyun.starmate.feature.mobileuse.FakeOverlay
import org.xmyun.starmate.feature.mobileuse.FakePrompts
import org.xmyun.starmate.feature.mobileuse.FakeRouter
import org.xmyun.starmate.feature.mobileuse.FixedClock
import org.xmyun.starmate.feature.mobileuse.FixedPolicies
import org.xmyun.starmate.feature.mobileuse.NOTES
import org.xmyun.starmate.feature.mobileuse.OWN
import org.xmyun.starmate.feature.mobileuse.guard.Guard
import org.xmyun.starmate.feature.mobileuse.model.AgentAction
import org.xmyun.starmate.feature.mobileuse.model.GuardRule
import org.xmyun.starmate.feature.mobileuse.model.ScreenState
import org.xmyun.starmate.feature.mobileuse.node
import org.xmyun.starmate.feature.mobileuse.screen
import org.xmyun.starmate.feature.mobileuse.service.Interruption
import org.xmyun.starmate.feature.mobileuse.tree.UiTreeSerializer

class PhoneTaskRunnerTest {

    private val notes = screen(
        nodes = listOf(node(0, "笔记"), node(1, "新建", clickable = true), node(2, "下一页", clickable = true)),
    )
    private val cashier = screen(nodes = listOf(node(0, "订单"), node(1, "确认付款", clickable = true)))
    private val chat = screen(nodes = listOf(node(0, "你好"), node(1, "发送", clickable = true)))

    private class Harness(
        val client: FakeLlmClient?,
        val device: FakeDevice,
        val overlay: FakeOverlay = FakeOverlay(),
        val ledger: FakeLedger = FakeLedger(),
        val logger: FakeLogger = FakeLogger(),
        limits: TaskLimits = TaskLimits(),
    ) {
        val returner = RecordingReturner(overlay)
        val router = FakeRouter(client)
        val runner = PhoneTaskRunner(
            router = router,
            ledger = ledger,
            device = device,
            overlay = overlay,
            guard = Guard(),
            policies = FixedPolicies(),
            logger = logger,
            prompts = FakePrompts(chinese = true),
            clock = FixedClock(),
            limits = limits,
            returner = returner,
        )
    }

    /** 记下被调用了几次，以及那时悬浮条是否还在。 */
    private class RecordingReturner(private val overlay: FakeOverlay) : AppReturner {
        var calls = 0
        var overlayHiddenAtCall: Boolean? = null
        override suspend fun bringBack() {
            calls++
            overlayHiddenAtCall = overlay.hidden
        }
    }

    @Test
    fun finishedTaskReturnsToStarmateBeforeOverlayIsHidden() = runTest {
        val h = Harness(FakeLlmClient(listOf(tap(1), done("建好了"))), FakeDevice { notes })
        h.runner.run("新建一条笔记")
        assertEquals(1, h.returner.calls)
        // 悬浮条还在时回去，系统才允许星伴从后台把自己调到前台
        assertEquals(false, h.returner.overlayHiddenAtCall)
        assertTrue(h.overlay.hidden)
    }

    @Test
    fun guardHaltAndLimitsAlsoReturnToStarmate() = runTest {
        val halted = Harness(FakeLlmClient(listOf(tap(1), tap(1))), FakeDevice { performed -> if (performed == 0) notes else cashier })
        assertTrue(halted.runner.run("x") is TaskOutcome.Halted)
        assertEquals(1, halted.returner.calls)

        val limited = Harness(FakeLlmClient(listOf(tap(2))), FakeDevice { notes })
        assertEquals(TaskOutcome.LimitReached(LimitKind.STEPS), limited.runner.run("x"))
        assertEquals(1, limited.returner.calls)
    }

    @Test
    fun userStopOrScreenOffStaysWhereTheUserIs() = runTest {
        val client = FakeLlmClient(listOf(tap(1)))
        val overlay = FakeOverlay()
        val stopped = Harness(client, FakeDevice { notes }, overlay = overlay)
        client.onCall = { n -> if (n == 2) overlay.onStop!!.invoke() }
        assertEquals(TaskOutcome.Stopped(StopReason.USER), stopped.runner.run("x"))
        assertEquals(0, stopped.returner.calls)

        val dark = FakeLlmClient(listOf(tap(1)))
        val device = FakeDevice { notes }
        val screenOff = Harness(dark, device)
        dark.onCall = { n -> if (n == 2) device.interruptionFlow.tryEmit(Interruption.SCREEN_OFF) }
        assertEquals(TaskOutcome.Stopped(StopReason.SCREEN_OFF), screenOff.runner.run("x"))
        assertEquals(0, screenOff.returner.calls)
    }

    private fun tap(i: Int) = """{"action":"tap","index":$i,"why":"x"}"""
    private fun done(s: String) = """{"action":"done","summary":"$s"}"""

    private fun FakeLlmClient.userText(call: Int): String = requests[call].messages.single().text

    @Test
    fun twoStepsThenDoneReturnsSummary() = runTest {
        val client = FakeLlmClient(listOf(tap(1), tap(2), done("建好了")))
        val h = Harness(client, FakeDevice { notes })
        val outcome = h.runner.run("新建一条笔记")
        assertEquals(TaskOutcome.Done("建好了"), outcome)
        assertEquals(2, h.device.performed.size)
        assertEquals(listOf(AgentAction.Tap(1, "x"), AgentAction.Tap(2, "x")), h.device.performed.map { it.action })
        assertEquals(listOf(ModelRole.VISION), h.router.roles)
        assertTrue(h.overlay.shown)
        assertTrue(h.overlay.hidden)
        assertEquals("DONE", h.logger.finishedOutcome)
        // 请求要求 JSON 输出，界面树在分隔标记之间
        val req = client.requests.first()
        assertTrue(req.jsonOutput)
        assertTrue(client.userText(0).contains(UiTreeSerializer.BEGIN_MARKER))
        assertTrue(client.userText(0).contains(UiTreeSerializer.END_MARKER))
        assertTrue(client.userText(0).contains("新建一条笔记"))
    }

    @Test
    fun everyModelCallRecordsMobileUseUsage() = runTest {
        val client = FakeLlmClient(listOf(tap(1), done("ok")), TokenUsage(inputTokens = 300, outputTokens = 40))
        val h = Harness(client, FakeDevice { notes })
        h.runner.run("x")
        assertEquals(2, h.ledger.entries.size)
        assertTrue(h.ledger.entries.all { it.purpose == TurnPurpose.MOBILE_USE })
        assertTrue(h.ledger.entries.all { it.inputTokens == 300 && it.outputTokens == 40 && it.model == "fake-vision" })
    }

    @Test
    fun screenTurnsIntoCashierMidwayHaltsWithG3AndNoMoreActions() = runTest {
        val client = FakeLlmClient(listOf(tap(1), tap(1), tap(1)))
        val h = Harness(client, FakeDevice { performed -> if (performed == 0) notes else cashier })
        val outcome = h.runner.run("x")
        assertTrue(outcome is TaskOutcome.Halted)
        assertEquals(GuardRule.G3, (outcome as TaskOutcome.Halted).finding.rule)
        assertEquals(1, h.device.performed.size)
        // 收银台的内容没有发给模型
        assertEquals(1, client.requests.size)
        assertEquals("HALT_G3", h.logger.finishedOutcome)
    }

    @Test
    fun cashierAppearingBetweenModelReplyAndActionIsCaughtByFreshRead() = runTest {
        val client = FakeLlmClient(listOf(tap(1)))
        lateinit var device: FakeDevice
        device = FakeDevice { if (device.reads == 0) notes else cashier }
        val h = Harness(client, device)
        val outcome = h.runner.run("x")
        assertEquals(GuardRule.G3, (outcome as TaskOutcome.Halted).finding.rule)
        assertEquals(0, h.device.performed.size)
    }

    @Test
    fun stepLimitEndsTaskBeforeStep26() = runTest {
        val client = FakeLlmClient(listOf(tap(2)))
        val h = Harness(client, FakeDevice { notes })
        val outcome = h.runner.run("x")
        assertEquals(TaskOutcome.LimitReached(LimitKind.STEPS), outcome)
        assertEquals(25, client.requests.size)
        assertEquals(25, h.device.performed.size)
    }

    @Test
    fun afterUserTapsStopNoMoreModelCalls() = runTest {
        val client = FakeLlmClient(listOf(tap(1)))
        val overlay = FakeOverlay()
        val h = Harness(client, FakeDevice { notes }, overlay = overlay)
        client.onCall = { n -> if (n == 2) overlay.onStop!!.invoke() }
        val outcome = h.runner.run("x")
        assertEquals(TaskOutcome.Stopped(StopReason.USER), outcome)
        assertEquals(2, client.requests.size)
        // 第二次调用的回复没有被执行
        assertEquals(1, h.device.performed.size)
        assertTrue(overlay.hidden)
    }

    @Test
    fun screenOffStopsTask() = runTest {
        val client = FakeLlmClient(listOf(tap(1)))
        val device = FakeDevice { notes }
        val h = Harness(client, device)
        client.onCall = { n -> if (n == 2) device.interruptionFlow.tryEmit(Interruption.SCREEN_OFF) }
        val outcome = h.runner.run("x")
        assertEquals(TaskOutcome.Stopped(StopReason.SCREEN_OFF), outcome)
        assertEquals(2, client.requests.size)
        assertEquals(1, device.performed.size)
    }

    @Test
    fun declinedConfirmationIsNotExecutedAndModelIsTold() = runTest {
        val client = FakeLlmClient(listOf(tap(1), done("没发")))
        val overlay = FakeOverlay(ArrayDeque(listOf(false)))
        val h = Harness(client, FakeDevice { chat }, overlay = overlay)
        val outcome = h.runner.run("发消息")
        assertEquals(TaskOutcome.Done("没发"), outcome)
        assertEquals(0, h.device.performed.size)
        assertEquals(1, overlay.questions.size)
        assertTrue(overlay.questions.single().contains("发送"))
        assertTrue(client.userText(1).contains("用户没有同意"))
        assertTrue(h.logger.steps.any { it.verdict == "USER_DECLINED" })
    }

    @Test
    fun approvedConfirmationIsExecutedAfterFreshCheck() = runTest {
        val client = FakeLlmClient(listOf(tap(1), done("发了")))
        val overlay = FakeOverlay(ArrayDeque(listOf(true)))
        val h = Harness(client, FakeDevice { chat }, overlay = overlay)
        h.runner.run("发消息")
        assertEquals(1, h.device.performed.size)
        assertEquals("发送", h.device.performed.single().target?.text)
        // 确认前后各取一次屏幕
        assertTrue(h.device.reads >= 3)
    }

    @Test
    fun threeParseFailuresInARowEndTask() = runTest {
        val client = FakeLlmClient(listOf("I will tap it", "{oops", "still no json"))
        val h = Harness(client, FakeDevice { notes })
        val outcome = h.runner.run("x")
        assertEquals(TaskOutcome.Failed(FailReason.PARSE_FAILURES), outcome)
        assertEquals(3, client.requests.size)
        assertTrue(client.userText(1).contains("无法使用"))
        assertEquals(0, h.device.performed.size)
    }

    @Test
    fun tokenLimitEndsTask() = runTest {
        val client = FakeLlmClient(listOf(tap(2)), TokenUsage(inputTokens = 55_000, outputTokens = 5_000))
        val h = Harness(client, FakeDevice { notes })
        val outcome = h.runner.run("x")
        assertEquals(TaskOutcome.LimitReached(LimitKind.TOKENS), outcome)
        assertEquals(3, client.requests.size)
        assertEquals(3, h.ledger.entries.size)
        assertEquals(2, h.device.performed.size)
    }

    @Test
    fun blockedForegroundAtStartNeverCallsModelOrReadsScreen() = runTest {
        val client = FakeLlmClient(listOf(tap(1)))
        val h = Harness(client, FakeDevice { screen(pkg = "com.eg.android.AlipayGphone") })
        val outcome = h.runner.run("x")
        assertEquals(GuardRule.G2, (outcome as TaskOutcome.Halted).finding.rule)
        assertEquals(0, client.requests.size)
        assertEquals(0, h.device.reads)
        assertEquals(0, h.device.performed.size)
    }

    @Test
    fun startingFromStarmateIsBlindAndCanOnlyOpenAllowedApp() = runTest {
        val client = FakeLlmClient(listOf("""{"action":"open_app","package":"$NOTES"}""", done("打开了")))
        val own = ScreenState(packageName = OWN, appLabel = "星伴", nodes = listOf(node(0, "聊天内容")))
        val h = Harness(client, FakeDevice { performed -> if (performed == 0) own else notes })
        val outcome = h.runner.run("打开笔记")
        assertEquals(TaskOutcome.Done("打开了"), outcome)
        assertEquals(AgentAction.OpenApp(NOTES), h.device.performed.single().action)
        val first = client.userText(0)
        assertFalse(first.contains(UiTreeSerializer.BEGIN_MARKER))
        assertFalse(first.contains("聊天内容"))
    }

    @Test
    fun targetChangedBeforeActionIsNotExecuted() = runTest {
        val changed = screen(nodes = listOf(node(0, "笔记"), node(1, "删除全部", clickable = true), node(2, "下一页", clickable = true)))
        val client = FakeLlmClient(listOf(tap(1), done("停")))
        lateinit var device: FakeDevice
        device = FakeDevice { if (device.reads == 0) notes else changed }
        val h = Harness(client, device)
        h.runner.run("x")
        assertEquals(0, h.device.performed.size)
        assertTrue(client.userText(1).contains("屏幕变了"))
    }

    @Test
    fun noModelConfiguredFailsWithoutTouchingDevice() = runTest {
        val h = Harness(null, FakeDevice { notes })
        assertEquals(TaskOutcome.Failed(FailReason.NO_MODEL), h.runner.run("x"))
        assertEquals(0, h.device.reads)
    }

    @Test
    fun askUserAnswerIsFedBack() = runTest {
        val client = FakeLlmClient(listOf("""{"action":"ask_user","question":"要新建吗？"}""", done("好")))
        val overlay = FakeOverlay(ArrayDeque(listOf(true)))
        val h = Harness(client, FakeDevice { notes }, overlay = overlay)
        h.runner.run("x")
        assertTrue(overlay.questions.single().contains("要新建吗？"))
        assertTrue(client.userText(1).contains("用户回答：是"))
    }
}
