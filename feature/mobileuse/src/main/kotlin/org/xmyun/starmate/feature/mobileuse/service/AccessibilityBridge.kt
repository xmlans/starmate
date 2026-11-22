package org.xmyun.starmate.feature.mobileuse.service

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** 无障碍服务实例和应用其他部分之间的桥。服务连上时登记，断开时注销并发出中断。 */
@Singleton
class AccessibilityBridge @Inject constructor() {

    private val serviceState = MutableStateFlow<StarmateAccessibilityService?>(null)
    val service: StateFlow<StarmateAccessibilityService?> = serviceState.asStateFlow()

    private val interruptionFlow = MutableSharedFlow<Interruption>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val interruptions: SharedFlow<Interruption> = interruptionFlow.asSharedFlow()

    @Volatile
    var lastEventAtMs: Long = 0L
        private set

    fun attach(service: StarmateAccessibilityService) {
        serviceState.value = service
    }

    fun detach(service: StarmateAccessibilityService) {
        if (serviceState.compareAndSet(service, null)) {
            interruptionFlow.tryEmit(Interruption.SERVICE_DISCONNECTED)
        }
    }

    fun screenOff() {
        interruptionFlow.tryEmit(Interruption.SCREEN_OFF)
    }

    /** 只记下“有窗口变化”的时间，用来判断界面是否稳定。不读事件内容。 */
    fun onWindowEvent() {
        lastEventAtMs = SystemClock.elapsedRealtime()
    }
}
