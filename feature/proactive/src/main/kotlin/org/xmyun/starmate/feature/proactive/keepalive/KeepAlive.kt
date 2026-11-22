package org.xmyun.starmate.feature.proactive.keepalive

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import org.xmyun.starmate.core.model.AppEdition
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.feature.proactive.R
import org.xmyun.starmate.feature.proactive.di.ProactiveEntryPoint
import org.xmyun.starmate.feature.proactive.notify.ProactiveChannels
import org.xmyun.starmate.feature.proactive.prefs.ProactivePrefs
import org.xmyun.starmate.feature.proactive.ui.ProactiveRoutes

/**
 * 保活：前台服务加一条低优先级常驻通知，给杀后台严重的系统用。本身不做任何事。
 * Android 14+ 需要声明前台服务类型 specialUse（清单里同时声明了 PROPERTY_SPECIAL_USE_FGS_SUBTYPE）。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ProactiveChannels.ensure(this)
        val entry = EntryPointAccessors.fromApplication(applicationContext, ProactiveEntryPoint::class.java)
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_SETTINGS,
            entry.appNavigator().intentFor(ProactiveRoutes.SETTINGS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, ProactiveChannels.KEEPALIVE)
            .setSmallIcon(R.drawable.proactive_ic_notification)
            .setContentTitle(getString(R.string.proactive_keepalive_notification_title))
            .setContentText(getString(R.string.proactive_keepalive_notification_text))
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            START_STICKY
        } catch (e: Exception) {
            // Android 12+ 从后台启动前台服务会被拒绝
            entry.diagLog().log("proactive", "keep-alive start failed: ${e.javaClass.simpleName}")
            stopSelf()
            START_NOT_STICKY
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 0x5703
        const val REQUEST_OPEN_SETTINGS = 0x5704
    }
}

@Singleton
class KeepAliveController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: ProactivePrefs,
    private val diag: DiagLog,
    private val edition: AppEdition,
) {
    /** 保活开关和总开关都打开时运行。这个版本不带保活服务时（Play 版）什么也不做。 */
    suspend fun syncWithPrefs() {
        if (!edition.keepAlive) return
        if (prefs.keepAlive.first() && prefs.enabled.first()) start() else stop()
    }

    private fun start() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
        } catch (e: Exception) {
            diag.log("proactive", "keep-alive start rejected: ${e.javaClass.simpleName}")
        }
    }

    private fun stop() {
        context.stopService(Intent(context, KeepAliveService::class.java))
    }
}
