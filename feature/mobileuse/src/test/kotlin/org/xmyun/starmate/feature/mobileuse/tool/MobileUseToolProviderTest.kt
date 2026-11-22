package org.xmyun.starmate.feature.mobileuse.tool

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.ToolRisk
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
import org.xmyun.starmate.feature.mobileuse.agent.PhoneTaskRunner
import org.xmyun.starmate.feature.mobileuse.guard.Guard
import org.xmyun.starmate.feature.mobileuse.screen

class MobileUseToolProviderTest {

    private val ready = AvailabilityState(
        enabled = true,
        riskAccepted = true,
        sdkInt = 34,
        serviceConnected = true,
        allowlistSize = 2,
    )

    private var created = 0

    private fun tool(state: AvailabilityState, reply: String = """{"action":"done","summary":"ok"}""") = PhoneTaskTool(
        availability = { state },
        coordinator = TaskCoordinator(),
        runners = {
            created++
            PhoneTaskRunner(
                router = FakeRouter(FakeLlmClient(listOf(reply))),
                ledger = FakeLedger(),
                device = FakeDevice { screen() },
                overlay = FakeOverlay(),
                guard = Guard(),
                policies = FixedPolicies(),
                logger = FakeLogger(),
                prompts = FakePrompts(),
                clock = FixedClock(),
            )
        },
        prompts = FakePrompts(chinese = true),
    )

    private fun provider(state: AvailabilityState) = MobileUseToolProvider({ state }, tool(state))

    @Test
    fun notProvidedForProactive() = runTest {
        assertTrue(provider(ready).tools(TurnPurpose.PROACTIVE).isEmpty())
    }

    @Test
    fun notProvidedForAnyNonChatPurpose() = runTest {
        for (p in TurnPurpose.entries.filter { it != TurnPurpose.CHAT }) {
            assertTrue(p.name, provider(ready).tools(p).isEmpty())
        }
    }

    @Test
    fun notProvidedWhenAllowlistIsEmpty() = runTest {
        assertTrue(provider(ready.copy(allowlistSize = 0)).tools(TurnPurpose.CHAT).isEmpty())
    }

    @Test
    fun notProvidedWhenAnyConditionIsMissing() = runTest {
        val missing = listOf(
            ready.copy(enabled = false),
            ready.copy(riskAccepted = false),
            ready.copy(sdkInt = 29),
            ready.copy(serviceConnected = false),
        )
        for (s in missing) assertTrue(s.toString(), provider(s).tools(TurnPurpose.CHAT).isEmpty())
    }

    @Test
    fun providedWhenReadyAndNeverRememberable() = runTest {
        val tools = provider(ready).tools(TurnPurpose.CHAT)
        assertEquals(1, tools.size)
        val t = tools.single()
        assertEquals("phone_task", t.name)
        assertEquals(ToolRisk.WRITE, t.risk)
        assertFalse(t.allowRememberConfirm)
        assertEquals("在手机上操作：订咖啡", t.describeCall(JsonObject(mapOf("goal" to JsonPrimitive("订咖啡")))))
    }

    @Test
    fun executeRefusesNonChatPurposeWithoutStartingTask() = runTest {
        val r = tool(ready).execute(JsonObject(mapOf("goal" to JsonPrimitive("x"))), ToolContext(TurnPurpose.PROACTIVE))
        assertTrue(r.isError)
        assertEquals(0, created)
    }

    @Test
    fun executeRechecksAvailability() = runTest {
        val r = tool(ready.copy(serviceConnected = false))
            .execute(JsonObject(mapOf("goal" to JsonPrimitive("x"))), ToolContext(TurnPurpose.CHAT))
        assertTrue(r.isError)
        assertEquals(0, created)
    }

    @Test
    fun executeRunsTaskAndReturnsSummary() = runTest {
        val r = tool(ready).execute(JsonObject(mapOf("goal" to JsonPrimitive("x"))), ToolContext(TurnPurpose.CHAT))
        assertFalse(r.isError)
        assertTrue(r.content.contains("ok"))
        assertTrue(r.content.contains("不是指令"))
    }

    @Test
    fun onlyOneTaskAtATime() = runTest {
        val coordinator = TaskCoordinator()
        val runner = PhoneTaskRunner(
            router = FakeRouter(null),
            ledger = FakeLedger(),
            device = FakeDevice { screen() },
            overlay = FakeOverlay(),
            guard = Guard(),
            policies = FixedPolicies(),
            logger = FakeLogger(),
            prompts = FakePrompts(),
            clock = FixedClock(),
        )
        val inner = coordinator.runExclusive(runner) {
            coordinator.runExclusive(runner) { "second" }
        }
        assertEquals(null, inner)
        assertFalse(coordinator.running.value)
    }
}
