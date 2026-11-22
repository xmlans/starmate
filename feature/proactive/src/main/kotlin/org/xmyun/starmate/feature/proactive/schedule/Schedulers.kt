package org.xmyun.starmate.feature.proactive.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.feature.proactive.platform.RandomSource
import org.xmyun.starmate.feature.proactive.work.CareWorker
import org.xmyun.starmate.feature.proactive.work.CheckInWorker

/** 约定关心的闹钟。真机才能验证，主流程只依赖这个接口。 */
interface CareScheduler {
    fun schedule(careId: Long, at: Instant)
    fun cancel(careId: Long)
}

/** 随机问候的周期任务。 */
interface CheckInScheduler {
    fun enable()
    fun disable()
}

/** 用 AlarmManager.setAndAllowWhileIdle：不精确闹钟，不需要精确闹钟权限，Doze 下也会触发（有频率限制）。 */
@Singleton
class AlarmCareScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diag: DiagLog,
) : CareScheduler {

    override fun schedule(careId: Long, at: Instant) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pendingIntent(careId, create = true) ?: return
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        } catch (e: Exception) {
            diag.log(TAG, "schedule care $careId failed: ${e.javaClass.simpleName}")
        }
    }

    override fun cancel(careId: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pendingIntent(careId, create = false) ?: return
        alarms.cancel(intent)
        intent.cancel()
    }

    private fun pendingIntent(careId: Long, create: Boolean): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_CARE)
            // data 让每条约定的 PendingIntent 互不覆盖
            .setData(Uri.parse("starmate-care://care/$careId"))
            .putExtra(AlarmReceiver.EXTRA_CARE_ID, careId)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, careId.toInt(), intent, flags)
    }

    private companion object {
        const val TAG = "proactive"
    }
}

@Singleton
class WorkCheckInScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val random: RandomSource,
) : CheckInScheduler {

    override fun enable() {
        // 周期 3 小时，弹性窗口 1 小时，首次再随机延后最多 1 小时
        val request = PeriodicWorkRequestBuilder<CheckInWorker>(3, TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .setInitialDelay(random.nextLong(0, 60), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(CHECK_IN_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun disable() {
        WorkManager.getInstance(context).cancelUniqueWork(CHECK_IN_WORK)
    }

    companion object {
        const val CHECK_IN_WORK = "proactive-check-in"
    }
}

object CareWork {
    fun enqueue(context: Context, careId: Long) {
        val request = OneTimeWorkRequestBuilder<CareWorker>()
            .setInputData(workDataOf(CareWorker.KEY_CARE_ID to careId))
            .setConstraints(networkConstraints())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("proactive-care-$careId", ExistingWorkPolicy.KEEP, request)
    }
}

private fun networkConstraints(): Constraints =
    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
