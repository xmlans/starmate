package org.xmyun.starmate.feature.mobileuse.tool

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.xmyun.starmate.feature.mobileuse.agent.PhoneTaskRunner
import org.xmyun.starmate.feature.mobileuse.agent.StopReason

/** 保证同一时间只有一个手机操作任务，并让界面能看到、停止当前任务。 */
@Singleton
class TaskCoordinator @Inject constructor() {
    private val current = AtomicReference<PhoneTaskRunner?>(null)
    private val runningState = MutableStateFlow(false)
    val running: StateFlow<Boolean> = runningState.asStateFlow()

    /** 已有任务在跑时返回 null，不执行 [block]。 */
    suspend fun <T> runExclusive(runner: PhoneTaskRunner, block: suspend (PhoneTaskRunner) -> T): T? {
        if (!current.compareAndSet(null, runner)) return null
        runningState.value = true
        try {
            return block(runner)
        } finally {
            current.set(null)
            runningState.value = false
        }
    }

    fun stopCurrent() {
        current.get()?.stop(StopReason.USER)
    }
}
