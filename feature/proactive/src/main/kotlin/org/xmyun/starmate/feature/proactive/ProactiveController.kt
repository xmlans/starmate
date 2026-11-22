package org.xmyun.starmate.feature.proactive

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.model.AppState
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.feature.proactive.keepalive.KeepAliveController
import org.xmyun.starmate.feature.proactive.notify.ProactiveNotifier
import org.xmyun.starmate.feature.proactive.prefs.ProactivePrefs
import org.xmyun.starmate.feature.proactive.schedule.CareRescheduler

/** 开关切换带来的副作用：登记或撤掉闹钟、周期任务、保活服务。 */
@Singleton
class ProactiveController @Inject constructor(
    private val prefs: ProactivePrefs,
    private val companionPrefs: CompanionPrefs,
    private val rescheduler: CareRescheduler,
    private val keepAlive: KeepAliveController,
) {
    suspend fun setEnabled(value: Boolean) {
        prefs.setEnabled(value)
        rescheduler.rescheduleAll()
        keepAlive.syncWithPrefs()
    }

    // 观察者也会对齐；这里同步做一次，保证设置页返回前闹钟已经撤掉或登记好
    suspend fun setClinginess(value: Clinginess) {
        companionPrefs.setClinginess(value)
        rescheduler.rescheduleAll()
    }

    suspend fun setKeepAlive(value: Boolean) {
        prefs.setKeepAlive(value)
        keepAlive.syncWithPrefs()
    }
}

/**
 * 进程内只做一次的启动工作：补登记闹钟和周期任务；App 回到前台时清掉她的消息通知；
 * 界面可见后按设置启动保活服务（Android 12+ 不允许从后台启动前台服务）。
 * 由 ProactiveFeatureEntry 在 MainActivity 注入时触发。
 */
@Singleton
class ProactiveBootstrap @Inject constructor(
    private val rescheduler: CareRescheduler,
    private val keepAlive: KeepAliveController,
    private val notifier: ProactiveNotifier,
    private val appState: AppState,
    private val diag: DiagLog,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        // 启动时按当前设置对齐一次，之后总开关或粘人程度在“启用/停用”之间切换时再对齐（含其他模块改粘人程度）
        scope.launch { rescheduler.followSettings() }
        scope.launch {
            appState.isForeground.collect { foreground -> if (foreground) notifier.cancelAll() }
        }
        scope.launch {
            appState.isForeground.first { it }
            try {
                keepAlive.syncWithPrefs()
            } catch (e: Exception) {
                diag.log(TAG, "keep-alive sync failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "proactive"
    }
}
