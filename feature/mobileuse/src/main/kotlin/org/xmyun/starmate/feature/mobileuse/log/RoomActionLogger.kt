package org.xmyun.starmate.feature.mobileuse.log

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** 把任务和每一步写进 mobileuse.db，最多保留最近 [KEEP_TASKS] 个任务。 */
@Singleton
class RoomActionLogger @Inject constructor(
    private val db: ActionLogDatabase,
) : ActionLogger {

    private val dao get() = db.dao()

    override suspend fun startTask(goal: String, at: Instant): Long {
        val id = dao.insertTask(TaskEntity(goal = goal, startedAt = at.toEpochMilli()))
        dao.trimTasks(KEEP_TASKS)
        dao.trimOrphanSteps()
        return id
    }

    override suspend fun logStep(taskId: Long, step: StepLog) {
        dao.insertStep(
            StepEntity(
                taskId = taskId,
                at = step.at.toEpochMilli(),
                packageName = step.packageName,
                appLabel = step.appLabel,
                action = step.action,
                verdict = step.verdict,
                detail = step.detail,
            ),
        )
    }

    override suspend fun finishTask(taskId: Long, at: Instant, outcome: String, detail: String) {
        dao.finishTask(taskId, at.toEpochMilli(), outcome, detail)
    }

    fun observeTasks(): Flow<List<TaskEntity>> = dao.observeTasks(KEEP_TASKS)

    fun observeSteps(taskId: Long): Flow<List<StepEntity>> = dao.observeSteps(taskId)

    suspend fun clear() {
        dao.clearSteps()
        dao.clearTasks()
    }

    companion object {
        const val KEEP_TASKS = 100
    }
}
