package org.xmyun.starmate.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 用户对哪些 WRITE 工具勾选过“以后不再问”。只存工具名。
 * allowRememberConfirm == false 的工具由引擎保证永远不记、也永远不按记住处理。
 * 设置页用它列出并撤销已记住的授权。
 */
interface ToolConsentStore {
    val remembered: Flow<Set<String>>
    suspend fun isRemembered(toolName: String): Boolean
    suspend fun remember(toolName: String)
    suspend fun forget(toolName: String)
}

data class ConfirmRequest(
    val id: String,
    val toolName: String,
    val summary: String,
    val allowRemember: Boolean,
)

data class ConfirmDecision(val approved: Boolean, val remember: Boolean = false)

interface ConfirmationBroker {
    /** 聊天界面观察它来显示确认卡片。 */
    val pending: StateFlow<ConfirmRequest?>

    /** 挂起直到用户决定。没有界面在前台时立即返回未批准。 */
    suspend fun confirm(request: ConfirmRequest): ConfirmDecision

    /** 聊天界面在用户点按钮后调用。 */
    fun resolve(requestId: String, decision: ConfirmDecision)
}
