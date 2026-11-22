package org.xmyun.starmate.feature.mobileuse.overlay

/** 任务期间的悬浮层：常驻的“星伴正在操作 · 停止”条，以及只有“是 / 否”的确认框。 */
interface TaskOverlay {
    /** 显示悬浮条。用户点“停止”时调用 [onStop]。 */
    suspend fun showRunning(onStop: () -> Unit)

    suspend fun hide()

    /** 弹出“是 / 否”确认并挂起到用户选择。被关闭、超时或悬浮层不可用时视为“否”。 */
    suspend fun confirm(message: String): Boolean
}
