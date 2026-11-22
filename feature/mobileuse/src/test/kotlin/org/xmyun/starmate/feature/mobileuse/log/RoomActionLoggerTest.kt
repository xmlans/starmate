package org.xmyun.starmate.feature.mobileuse.log

import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomActionLoggerTest {

    private val db = ActionLogDatabase.build(ApplicationProvider.getApplicationContext(), "mobileuse-${UUID.randomUUID()}.db")
    private val logger = RoomActionLogger(db)

    @After
    fun close() = db.close()

    @Test
    fun tasksAndStepsAreRecorded() = runTest {
        val t0 = Instant.parse("2026-11-12T10:00:00Z")
        val id = logger.startTask("新建笔记", t0)
        logger.logStep(id, StepLog(t0.plusSeconds(1), "com.example.notes", "Notes", "tap [1]", "ALLOW"))
        logger.logStep(id, StepLog(t0.plusSeconds(2), "com.example.notes", "Notes", "tap [2]", "HALT", "收银台"))
        logger.finishTask(id, t0.plusSeconds(3), "HALT_G3", "中止")
        val task = logger.observeTasks().first().single()
        assertEquals("HALT_G3", task.outcome)
        val steps = logger.observeSteps(id).first()
        assertEquals(listOf("ALLOW", "HALT"), steps.map { it.verdict })
        logger.clear()
        assertEquals(0, logger.observeTasks().first().size)
    }

    @Test
    fun onlyRecentTasksAreKept() = runTest {
        val t0 = Instant.parse("2026-11-12T10:00:00Z")
        repeat(RoomActionLogger.KEEP_TASKS + 5) { logger.startTask("t$it", t0.plusSeconds(it.toLong())) }
        assertEquals(RoomActionLogger.KEEP_TASKS, logger.observeTasks().first().size)
    }
}
