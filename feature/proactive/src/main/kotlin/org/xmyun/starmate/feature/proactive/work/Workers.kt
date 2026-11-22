package org.xmyun.starmate.feature.proactive.work

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.feature.proactive.R
import org.xmyun.starmate.feature.proactive.notify.ProactiveChannels

/** 约定的闹钟触发后由 AlarmReceiver 启动（加急、要求联网）。 */
@HiltWorker
class CareWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: ProactiveRunner,
    private val diag: DiagLog,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val careId = inputData.getLong(KEY_CARE_ID, -1L)
        if (careId <= 0) return Result.success()
        return try {
            runner.runCare(careId)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 顺延逻辑没来得及执行（例如数据库异常），交给 WorkManager 退避重试
            diag.log(TAG, "care worker failed: ${e.javaClass.simpleName}")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        }
    }

    /** Android 12 以下加急任务以前台服务运行，需要一条通知。 */
    override suspend fun getForegroundInfo(): ForegroundInfo = workForegroundInfo(applicationContext)

    companion object {
        const val KEY_CARE_ID = "care_id"
        private const val TAG = "proactive"
        private const val MAX_RETRIES = 3
    }
}

/** 约每 3 小时一次的随机问候。 */
@HiltWorker
class CheckInWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: ProactiveRunner,
    private val diag: DiagLog,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            runner.runCheckIn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 周期任务下次还会来，失败不重试
            diag.log("proactive", "check-in worker failed: ${e.javaClass.simpleName}")
        }
        return Result.success()
    }
}

private const val WORK_NOTIFICATION_ID = 0x5701

internal fun workForegroundInfo(context: Context): ForegroundInfo {
    ProactiveChannels.ensure(context)
    val notification = NotificationCompat.Builder(context, ProactiveChannels.KEEPALIVE)
        .setSmallIcon(R.drawable.proactive_ic_notification)
        .setContentTitle(context.getString(R.string.proactive_work_notification_title))
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setSilent(true)
        .setOngoing(true)
        .build()
    return ForegroundInfo(WORK_NOTIFICATION_ID, notification)
}
