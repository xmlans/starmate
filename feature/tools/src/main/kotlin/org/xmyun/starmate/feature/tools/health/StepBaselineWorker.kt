package org.xmyun.starmate.feature.tools.health

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.common.ToolGroup

/** 每小时记录一次计步传感器的累计值，供 StepFallback 计算每天的步数。 */
@HiltWorker
class StepBaselineWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val gate: GroupGate,
    private val sensor: StepSensorReader,
    private val fallback: StepFallback,
    private val diag: DiagLog,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!gate.usable(ToolGroup.HEALTH)) return Result.success()
        return try {
            sensor.read()?.let { fallback.record(it) }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.log(TAG, "step sample failed: ${e.javaClass.simpleName}")
            Result.success()
        }
    }

    companion object {
        const val UNIQUE_NAME = "tools_step_baseline"
        private const val TAG = "tools"
    }
}

/** 健康组启用时安排、关闭时取消计步采样任务。 */
class StepBaselineScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedule() {
        val request = PeriodicWorkRequestBuilder<StepBaselineWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(StepBaselineWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(StepBaselineWorker.UNIQUE_NAME)
    }
}
