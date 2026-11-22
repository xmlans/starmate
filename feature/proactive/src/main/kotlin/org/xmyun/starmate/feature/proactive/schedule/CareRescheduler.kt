package org.xmyun.starmate.feature.proactive.schedule

import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.feature.proactive.model.Care
import org.xmyun.starmate.feature.proactive.model.CareStatus
import org.xmyun.starmate.feature.proactive.model.LogOutcome
import org.xmyun.starmate.feature.proactive.model.LogReasons
import org.xmyun.starmate.feature.proactive.model.LogSource
import org.xmyun.starmate.feature.proactive.model.SendLogEntry
import org.xmyun.starmate.feature.proactive.platform.RandomSource
import org.xmyun.starmate.feature.proactive.prefs.ProactivePrefs
import org.xmyun.starmate.feature.proactive.store.CareStore
import org.xmyun.starmate.feature.proactive.store.SendLogStore

/** 主动消息是否处于启用状态：总开关打开且粘人程度不是“关”。 */
fun isProactiveActive(enabled: Boolean, clinginess: Clinginess): Boolean = enabled && clinginess != Clinginess.OFF

/**
 * 按当前设置对齐全部闹钟和周期任务。
 * - 启用：每条待发约定都登记闹钟；时间已过的在 1–5 分钟内触发；从原定时间起超过 3 天的放弃并记录。
 * - 停用（总开关关或粘人程度为“关”）：撤掉全部约定闹钟和随机问候任务，约定保持待发，即“挂起”。
 * 开机、改时间、导入、设置变化、进程启动时调用。
 */
@Singleton
class CareRescheduler @Inject constructor(
    private val cares: CareStore,
    private val logs: SendLogStore,
    private val scheduler: CareScheduler,
    private val checkIn: CheckInScheduler,
    private val prefs: ProactivePrefs,
    private val companionPrefs: CompanionPrefs,
    private val random: RandomSource,
    private val clock: Clock,
    private val diag: DiagLog,
) {
    // 设置观察、开机广播、导入可能同时触发，串行执行，避免同一条约定被放弃两次
    private val mutex = Mutex()

    val active: Flow<Boolean> =
        combine(prefs.enabled, companionPrefs.clinginess, ::isProactiveActive).distinctUntilChanged()

    suspend fun rescheduleAll() = mutex.withLock {
        val active = active.first()
        val now = clock.instant()
        var registered = 0
        for (care in cares.pending()) {
            if (!active) {
                scheduler.cancel(care.id)
                continue
            }
            if (CareDeferral.isExpired(care.scheduledFor, now)) {
                scheduler.cancel(care.id)
                expire(care)
                continue
            }
            scheduler.schedule(care.id, alarmTime(care.nextAttemptAt, now))
            registered++
        }
        if (active) checkIn.enable() else checkIn.disable()
        diag.log(TAG, "rescheduled: active=$active cares=$registered")
    }

    /** 进程存活期间跟随设置变化对齐；开始收集时按当前设置先对齐一次。不会返回。 */
    suspend fun followSettings() {
        active.collect {
            try {
                rescheduleAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diag.log(TAG, "reschedule failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** 导入前撤掉旧库里约定的闹钟。 */
    suspend fun cancelAllAlarms() {
        for (care in cares.pending()) scheduler.cancel(care.id)
    }

    suspend fun expire(care: Care) {
        val now = clock.instant()
        cares.update(care.copy(status = CareStatus.EXPIRED, updatedAt = now))
        logs.add(
            SendLogEntry(
                at = now,
                source = LogSource.CARE,
                careId = care.id,
                topic = care.topic,
                outcome = LogOutcome.GAVE_UP,
                reason = LogReasons.EXPIRED,
            ),
        )
    }

    // 未到时间的按原计划；已过时间的（挂起期间错过或进程被杀错过）在几分钟内带随机偏移触发
    private fun alarmTime(next: Instant, now: Instant): Instant =
        if (next.isAfter(now)) next else Jitter.after(now.plus(PAST_DUE_MIN_DELAY), random, PAST_DUE_SPREAD)

    companion object {
        private const val TAG = "proactive"
        val PAST_DUE_MIN_DELAY: Duration = Duration.ofMinutes(1)
        val PAST_DUE_SPREAD: Duration = Duration.ofMinutes(4)
    }
}
