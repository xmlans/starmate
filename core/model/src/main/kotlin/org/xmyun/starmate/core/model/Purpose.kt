package org.xmyun.starmate.core.model

enum class TurnPurpose {
    CHAT, ONBOARDING, PROACTIVE, DIARY, MEMORY, MOBILE_USE;

    /** 后台用途：引擎不给工具，并检查每日预算。 */
    val isBackground: Boolean
        get() = this == PROACTIVE || this == DIARY || this == MEMORY
}

enum class ModelRole { CHAT, BACKGROUND, VISION, EMBEDDING, TTS, STT }

enum class ToolRisk { READ, WRITE }
