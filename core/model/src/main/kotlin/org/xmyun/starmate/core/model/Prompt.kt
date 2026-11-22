package org.xmyun.starmate.core.model

object PromptOrder {
    const val MEMORY = 100
    const val TOOLS = 200
    const val VOICE = 300
    const val PROACTIVE = 400
    const val ONBOARDING = 900
}

interface PromptContributor {
    /** 小的在前。取值见 [PromptOrder]。 */
    val order: Int

    /** 进 system prompt 的稳定部分。内容没变时必须逐字不变。不参与时返回 null。 */
    suspend fun stable(purpose: TurnPurpose): String? = null

    /** 附在本轮用户消息之后，不写入历史。不参与时返回 null。 */
    suspend fun dynamic(purpose: TurnPurpose, userText: String?): String? = null
}
