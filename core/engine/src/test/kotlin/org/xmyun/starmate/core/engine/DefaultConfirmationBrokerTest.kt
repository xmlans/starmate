@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.xmyun.starmate.core.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.core.model.ConfirmDecision
import org.xmyun.starmate.core.model.ConfirmRequest

class DefaultConfirmationBrokerTest {

    private fun request(id: String, allowRemember: Boolean = true) =
        ConfirmRequest(id = id, toolName = "alarm_set", summary = "定 7 点的闹钟", allowRemember = allowRemember)

    @Test
    fun resolveCompletesConfirm() = runTest {
        val broker = DefaultConfirmationBroker(FakeAppState())
        val result = async { broker.confirm(request("r1")) }
        runCurrent()
        assertEquals("r1", broker.pending.value?.id)

        broker.resolve("r1", ConfirmDecision(approved = true, remember = true))
        assertEquals(ConfirmDecision(approved = true, remember = true), result.await())
        assertNull(broker.pending.value)
    }

    @Test
    fun backgroundReturnsDeniedImmediately() = runTest {
        val broker = DefaultConfirmationBroker(FakeAppState(foreground = false))
        val decision = broker.confirm(request("r1"))
        assertFalse(decision.approved)
        assertNull(broker.pending.value)
    }

    @Test
    fun twoRequestsAreHandledInOrder() = runTest {
        val broker = DefaultConfirmationBroker(FakeAppState())
        val first = async { broker.confirm(request("a")) }
        val second = async { broker.confirm(request("b")) }
        runCurrent()
        assertEquals("a", broker.pending.value?.id)

        // 还没轮到的请求不能被提前处理
        broker.resolve("b", ConfirmDecision(approved = true))
        runCurrent()
        assertEquals("a", broker.pending.value?.id)

        broker.resolve("a", ConfirmDecision(approved = false))
        runCurrent()
        assertFalse(first.await().approved)
        assertEquals("b", broker.pending.value?.id)

        broker.resolve("b", ConfirmDecision(approved = true))
        assertTrue(second.await().approved)
        assertNull(broker.pending.value)
    }

    @Test
    fun rememberIsDroppedWhenNotAllowed() = runTest {
        val broker = DefaultConfirmationBroker(FakeAppState())
        val result = async { broker.confirm(request("r1", allowRemember = false)) }
        runCurrent()
        broker.resolve("r1", ConfirmDecision(approved = true, remember = true))
        assertEquals(ConfirmDecision(approved = true, remember = false), result.await())
    }

    @Test
    fun cancellationClearsPending() = runTest {
        val broker = DefaultConfirmationBroker(FakeAppState())
        val result = async { broker.confirm(request("r1")) }
        runCurrent()
        assertEquals("r1", broker.pending.value?.id)
        result.cancelAndJoin()
        assertNull(broker.pending.value)
    }
}
