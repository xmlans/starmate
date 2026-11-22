package org.xmyun.starmate.feature.chat.interview

import javax.inject.Inject
import kotlinx.coroutines.flow.first
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.PromptContributor
import org.xmyun.starmate.core.model.PromptOrder
import org.xmyun.starmate.core.model.TurnPurpose

/** 访谈期间往 system prompt 里加访谈提示词；访谈结束（DONE）后返回 null，提示词随即消失。 */
class OnboardingPromptContributor @Inject constructor(
    private val prefs: CompanionPrefs,
    private val texts: InterviewTexts,
) : PromptContributor {

    override val order: Int = PromptOrder.ONBOARDING

    override suspend fun stable(purpose: TurnPurpose): String? {
        if (purpose != TurnPurpose.ONBOARDING) return null
        if (prefs.onboardingState.first() != OnboardingState.INTERVIEWING) return null
        return texts.interviewPrompt()
    }
}
