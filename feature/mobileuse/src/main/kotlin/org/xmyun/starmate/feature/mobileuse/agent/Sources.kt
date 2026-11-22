package org.xmyun.starmate.feature.mobileuse.agent

import org.xmyun.starmate.feature.mobileuse.guard.Policy

/** 每一步重新生成守卫策略（白名单、默认短信应用、桌面应用可能随时变化）。 */
fun interface PolicyProvider {
    suspend fun current(): Policy
}

/** 操作模型的 system prompt，按系统语言读 res/raw 或 res/raw-zh。 */
interface PromptSource {
    val chinese: Boolean

    /** lookAvailable 为 false 时去掉 look 的说明。 */
    fun systemPrompt(lookAvailable: Boolean): String
}
