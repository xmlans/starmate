package org.xmyun.starmate.feature.diary.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.feature.diary.write.DiaryWindow
import org.xmyun.starmate.feature.diary.write.DiaryWriter

/** 每次安排一个一次性任务，时间是下一个 22:30–01:30 窗口里的随机时刻，要求联网。 */
@Singleton
class DiaryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {
    private fun request(): androidx.work.OneTimeWorkRequest {
        val now = ZonedDateTime.now(clock)
        val next = DiaryWindow.nextRun(now, Random.nextDouble())
        return OneTimeWorkRequestBuilder<DiaryWorker>()
            .setInitialDelay(Duration.between(now, next).toMinutes().coerceAtLeast(1), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
    }

    /** App 启动时调用：已经有任务在排队就保留。 */
    fun ensureScheduled() {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request())
    }

    /** 任务执行完（无论写没写）再安排下一次：排在正在运行的这一个之后，不会取消它。 */
    fun scheduleNext() {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
    }

    companion object {
        const val WORK_NAME = "diary_nightly"
    }
}

@HiltWorker
class DiaryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val writer: DiaryWriter,
    private val scheduler: DiaryScheduler,
    private val diag: DiagLog,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        try {
            writer.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.log("diary", "diary run crashed: ${e.javaClass.simpleName}")
        } finally {
            scheduler.scheduleNext()
        }
        return Result.success()
    }
}
