package org.xmyun.starmate.feature.chat.onboarding

import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.feature.chat.common.ChatRoutes

/** 引导流程该从哪一步开始、某一步之后去哪。返回 null 表示引导已完成，进入聊天。 */
object OnboardingSteps {

    fun firstStep(adultConfirmed: Boolean, hasChatModel: Boolean, state: OnboardingState): String? = when {
        !adultConfirmed -> ChatRoutes.ONBOARDING_WELCOME
        !hasChatModel -> ChatRoutes.ONBOARDING_MODEL
        state == OnboardingState.NOT_STARTED -> ChatRoutes.ONBOARDING_COMPANION
        else -> null
    }

    /** 成年确认之后：从备份恢复的用户可能已经配好模型或做完访谈。 */
    fun afterAdult(hasChatModel: Boolean, state: OnboardingState): String? =
        firstStep(adultConfirmed = true, hasChatModel = hasChatModel, state = state)

    fun afterModel(state: OnboardingState): String? =
        if (state == OnboardingState.NOT_STARTED) ChatRoutes.ONBOARDING_COMPANION else null
}
