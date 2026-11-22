package org.xmyun.starmate.feature.mobileuse.log

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionLogDao {
    @Insert
    suspend fun insertTask(task: TaskEntity): Long

    @Insert
    suspend fun insertStep(step: StepEntity): Long

    @Query("UPDATE tasks SET ended_at = :endedAt, outcome = :outcome, detail = :detail WHERE id = :id")
    suspend fun finishTask(id: Long, endedAt: Long, outcome: String, detail: String)

    @Query("SELECT * FROM tasks ORDER BY started_at DESC, id DESC LIMIT :limit")
    fun observeTasks(limit: Int): Flow<List<TaskEntity>>

    @Query("SELECT * FROM steps WHERE task_id = :taskId ORDER BY at ASC, id ASC")
    fun observeSteps(taskId: Long): Flow<List<StepEntity>>

    @Query("SELECT * FROM steps WHERE task_id = :taskId ORDER BY at ASC, id ASC")
    suspend fun steps(taskId: Long): List<StepEntity>

    @Query("DELETE FROM tasks")
    suspend fun clearTasks()

    @Query("DELETE FROM steps")
    suspend fun clearSteps()

    /** 只保留最近的若干个任务。 */
    @Query("DELETE FROM tasks WHERE id NOT IN (SELECT id FROM tasks ORDER BY started_at DESC, id DESC LIMIT :keep)")
    suspend fun trimTasks(keep: Int)

    @Query("DELETE FROM steps WHERE task_id NOT IN (SELECT id FROM tasks)")
    suspend fun trimOrphanSteps()
}
