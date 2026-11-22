package org.xmyun.starmate.core.model

import kotlinx.serialization.json.JsonObject

data class ToolContext(val purpose: TurnPurpose)

data class ToolResult(
    val content: String,
    val isError: Boolean = false,
)

interface Tool {
    /** 全局唯一，以设计文档里写的名字为准。只用小写字母、数字、下划线。 */
    val name: String
    val description: String

    /** JSON Schema，顶层 type 为 object。 */
    val parametersSchema: JsonObject
    val risk: ToolRisk

    /** false 时确认卡片不提供“以后不再问”。 */
    val allowRememberConfirm: Boolean get() = true

    /**
     * 这个工具返回的是不是外部来的、不可信的内容（网页、第三方服务写的文字）。
     * 这类内容里可能藏着冒充指令的文字。同一轮里一旦用过这类工具，引擎会收紧后面的调用：
     * 需要确认的工具不再认“以后不再问”，标了 [confirmAfterUntrusted] 的工具也要先问用户。
     */
    val returnsUntrustedContent: Boolean get() = false

    /**
     * 平时不用确认，但同一轮里出现过不可信内容之后要先问用户。
     * 给会留下长期影响的工具用（写进记忆、安排以后的事），防止网页里的文字借她的手留下东西。
     */
    val confirmAfterUntrusted: Boolean get() = false

    /** 给用户看的一句话，说明这次调用要做什么。用在工具活动标签上。 */
    fun describeCall(args: JsonObject): String = name

    /**
     * 确认卡片上的说明。要让用户看得出这次调用具体会做什么、把什么发出去，
     * 默认和 [describeCall] 相同；标签写得含糊的工具应当在这里给出细节。
     */
    fun describeConfirm(args: JsonObject): String = describeCall(args)

    /** 失败时返回 isError = true 的结果，不要抛异常。 */
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

interface ToolProvider {
    /** 返回该用途下当前可用的工具。功能关闭或权限未授予时不返回对应工具。 */
    suspend fun tools(purpose: TurnPurpose): List<Tool>
}
