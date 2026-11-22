package org.xmyun.starmate.feature.proactive.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.xmyun.starmate.feature.proactive.di.ProactiveEntryPoint

/** 约定的闹钟到点：交给要求联网的加急 CareWorker。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CARE) return
        val careId = intent.getLongExtra(EXTRA_CARE_ID, -1L)
        if (careId <= 0) return
        CareWork.enqueue(context, careId)
    }

    companion object {
        const val ACTION_CARE = "org.xmyun.starmate.feature.proactive.CARE_ALARM"
        const val EXTRA_CARE_ID = "care_id"
    }
}

/** 开机或 App 更新后闹钟会丢失：重新登记，并按设置恢复保活服务。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, ProactiveEntryPoint::class.java)
        runAsync(entry) {
            entry.rescheduler().rescheduleAll()
            entry.keepAliveController().syncWithPrefs()
        }
    }
}

/** 系统时间或时区变化后重新登记闹钟。 */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Unit
            else -> return
        }
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, ProactiveEntryPoint::class.java)
        runAsync(entry) { entry.rescheduler().rescheduleAll() }
    }
}

private fun BroadcastReceiver.runAsync(entry: ProactiveEntryPoint, block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            block()
        } catch (e: Exception) {
            entry.diagLog().log("proactive", "receiver work failed: ${e.javaClass.simpleName}")
        } finally {
            pending.finish()
        }
    }
}
