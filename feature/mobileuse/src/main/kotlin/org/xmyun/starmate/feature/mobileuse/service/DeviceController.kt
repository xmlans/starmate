package org.xmyun.starmate.feature.mobileuse.service

import kotlinx.coroutines.flow.Flow
import org.xmyun.starmate.feature.mobileuse.guard.ApprovedAction
import org.xmyun.starmate.feature.mobileuse.guard.ObservePermit
import org.xmyun.starmate.feature.mobileuse.model.Foreground
import org.xmyun.starmate.feature.mobileuse.model.RawObservation

enum class Interruption { SCREEN_OFF, SERVICE_DISCONNECTED }

/**
 * 真机操作的唯一出口，把无障碍服务和任务循环隔开。
 * 读界面要 [ObservePermit]，执行动作要 [ApprovedAction]，两者都只能由 Guard 创建。
 */
interface DeviceController {
    val isConnected: Boolean

    /** 屏幕熄灭、无障碍服务断开时发出。 */
    val interruptions: Flow<Interruption>

    /** 只取前台窗口的包名、应用名和同屏其他应用的包名，不读任何界面内容。取不到时返回 null。 */
    suspend fun foreground(): Foreground?

    /**
     * 读当前窗口的界面树，按需同时截图。
     * 许可已作废、或前台已不是 [ObservePermit.packageName] 时不读，返回 null。
     */
    suspend fun readScreen(permit: ObservePermit, withScreenshot: Boolean): RawObservation?

    /**
     * 执行守卫放行的动作。下列情况一律不执行并返回 false：
     * 许可已用过、快照已过期、前台已不是放行时的应用、目标节点已变成密码框。
     */
    suspend fun perform(approved: ApprovedAction): Boolean

    /** 等界面稳定（一段时间内没有窗口变化），最多等 [timeoutMs]。 */
    suspend fun awaitSettled(timeoutMs: Long)
}
