package org.xmyun.starmate.feature.voice.stt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.feature.voice.FakeDiagLog

@OptIn(ExperimentalCoroutinesApi::class)
class MicInputControllerTest {

    private class FakeStt(
        private val startOk: Boolean = true,
        private val result: SttResult = SttResult.Text("你好"),
    ) : SttEngine {
        var started = 0
        var finished = 0
        var cancelled = 0
        var onEnded: (() -> Unit)? = null

        override suspend fun start(onEnded: () -> Unit): Boolean {
            started++
            this.onEnded = onEnded
            return startOk
        }

        override suspend fun finish(): SttResult {
            finished++
            return result
        }

        override fun cancel() {
            cancelled++
        }
    }

    private fun TestScope.controller(engine: FakeStt) = MicInputController(
        factory = object : SttEngineFactory {
            override suspend fun create(): SttEngine = engine
        },
        diag = FakeDiagLog(),
        // 不用 backgroundScope：advanceUntilIdle 不会等后台任务
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        maxDurationMs = 60_000,
    )

    @Test
    fun toggleTwiceRecognizes() = runTest {
        val engine = FakeStt()
        val c = controller(engine)
        c.toggle()
        runCurrent()
        assertEquals(MicState.RECORDING, c.state.value)
        c.toggle()
        advanceUntilIdle()
        assertEquals(MicState.IDLE, c.state.value)
        assertEquals(MicEvent.Recognized("你好"), c.events.first())
        assertEquals(1, engine.finished)
    }

    @Test
    fun stopsAutomaticallyAfterMaxDuration() = runTest {
        val engine = FakeStt()
        val c = controller(engine)
        c.toggle()
        runCurrent()
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(MicState.IDLE, c.state.value)
        assertEquals(1, engine.finished)
    }

    @Test
    fun engineEndingByItselfDeliversResult() = runTest {
        val engine = FakeStt()
        val c = controller(engine)
        c.toggle()
        runCurrent()
        engine.onEnded!!.invoke()
        advanceUntilIdle()
        assertEquals(MicEvent.Recognized("你好"), c.events.first())
    }

    @Test
    fun startFailureReportsUnavailable() = runTest {
        val c = controller(FakeStt(startOk = false))
        c.toggle()
        advanceUntilIdle()
        assertEquals(MicState.IDLE, c.state.value)
        assertEquals(MicEvent.Failed(SttFailure.UNAVAILABLE), c.events.first())
    }

    @Test
    fun recognitionFailureReported() = runTest {
        val c = controller(FakeStt(result = SttResult.Failure(SttFailure.NETWORK)))
        c.toggle()
        runCurrent()
        c.toggle()
        advanceUntilIdle()
        assertEquals(MicEvent.Failed(SttFailure.NETWORK), c.events.first())
    }

    @Test
    fun cancelDiscardsRecording() = runTest {
        val engine = FakeStt()
        val c = controller(engine)
        c.toggle()
        runCurrent()
        c.cancel()
        advanceUntilIdle()
        assertEquals(MicState.IDLE, c.state.value)
        assertTrue(engine.cancelled >= 1)
        assertEquals(0, engine.finished)
    }
}
