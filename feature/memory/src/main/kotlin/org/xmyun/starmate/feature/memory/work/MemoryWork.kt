package org.xmyun.starmate.feature.memory.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.model.AppState
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.feature.memory.consolidate.Consolidator
import org.xmyun.starmate.feature.memory.model.MemorySettings

/** 请求一次整理。三种触发（每满 6 轮、退到后台 10 分钟、每晚）最后都走同一个唯一任务。 */
interface ConsolidateTrigger {
    fun requestNow(maintain: Boolean = false)
}

@Singleton
class ConsolidateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appState: AppState,
    private val settings: MemorySettings,
    private val clock: Clock,
) : ConsolidateTrigger {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** 开始观察前后台并确保每晚任务在排队。可以重复调用。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        ensureNightly()
        scope.launch {
            var wasForeground = appState.isForeground.value
            appState.isForeground.collect { foreground ->
                if (wasForeground && !foreground) scheduleIdle() else if (foreground) cancelIdle()
                wasForeground = foreground
            }
        }
    }

    /** 已有整理在排队或在跑时保留那一个（它会把待处理的原话整理完）。 */
    override fun requestNow(maintain: Boolean) {
        val request = OneTimeWorkRequestBuilder<ConsolidateWorker>()
            .setConstraints(network)
            .setInputData(workDataOf(ConsolidateWorker.KEY_MAINTAIN to maintain))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(CONSOLIDATE_WORK, ExistingWorkPolicy.KEEP, request)
    }

    private fun scheduleIdle() {
        val request = OneTimeWorkRequestBuilder<IdleConsolidateWorker>()
            .setInitialDelay(settings.idleMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(IDLE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun cancelIdle() {
        WorkManager.getInstance(context).cancelUniqueWork(IDLE_WORK)
    }

    private fun ensureNightly() {
        val now = ZonedDateTime.now(clock)
        var next = now.with(NIGHTLY_TIME)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<NightlyMemoryWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NIGHTLY_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val CONSOLIDATE_WORK = "memory_consolidate"
        const val IDLE_WORK = "memory_idle"
        const val NIGHTLY_WORK = "memory_nightly"
        private val NIGHTLY_TIME: LocalTime = LocalTime.of(3, 30)
    }
}

/** 真正执行整理的唯一任务。失败不让 WorkManager 重试：原话保留，下一次触发再整理。 */
@HiltWorker
class ConsolidateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val consolidator: Consolidator,
    private val diag: DiagLog,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        try {
            val outcome = consolidator.consolidatePending()
            diag.log(TAG, "consolidation run finished: ${outcome.javaClass.simpleName}")
            if (inputData.getBoolean(KEY_MAINTAIN, false)) consolidator.maintain()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.log(TAG, "consolidation run crashed: ${e.javaClass.simpleName}")
        }
        return Result.success()
    }

    companion object {
        const val KEY_MAINTAIN = "maintain"
        private const val TAG = "memory"
    }
}

/** 退到后台满 10 分钟：请求一次整理。 */
@HiltWorker
class IdleConsolidateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val trigger: ConsolidateScheduler,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        trigger.requestNow()
        return Result.success()
    }
}

/** 每晚一次：请求一次整理，并顺带做遗忘和归档维护。 */
@HiltWorker
class NightlyMemoryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val trigger: ConsolidateScheduler,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        trigger.requestNow(maintain = true)
        return Result.success()
    }
}
