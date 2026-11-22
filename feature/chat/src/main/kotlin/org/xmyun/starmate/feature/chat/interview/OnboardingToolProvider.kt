package org.xmyun.starmate.feature.chat.interview

import javax.inject.Inject
import javax.inject.Provider
import org.xmyun.starmate.core.model.Tool
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.model.TurnPurpose

/** 只在 ONBOARDING 用途下提供 finish_onboarding。 */
class OnboardingToolProvider @Inject constructor(
    // Provider：避免 引擎 → 工具 → 协调器 → 记忆 → 引擎 的依赖环
    private val finishTool: Provider<FinishOnboardingTool>,
) : ToolProvider {
    override suspend fun tools(purpose: TurnPurpose): List<Tool> =
        if (purpose == TurnPurpose.ONBOARDING) listOf(finishTool.get()) else emptyList()
}
