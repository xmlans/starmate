package org.xmyun.starmate.feature.mobileuse.log

import java.time.Instant

/** 操作日志的一步：时间、应用、动作、守卫结论。 */
data class StepLog(
    val at: Instant,
    val packageName: String?,
    val appLabel: String?,
    val action: String,
    /** ALLOW、CONFIRM、HALT、USER_APPROVED、USER_DECLINED、FAILED 等。 */
    val verdict: String,
    val detail: String? = null,
)

/** 任务循环写日志用的接口，生产实现是 [RoomActionLogger]。 */
interface ActionLogger {
    suspend fun startTask(goal: String, at: Instant): Long
    suspend fun logStep(taskId: Long, step: StepLog)
    suspend fun finishTask(taskId: Long, at: Instant, outcome: String, detail: String)
}
