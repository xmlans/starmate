package org.xmyun.starmate.feature.chat.interview

import java.time.Clock
import java.util.Optional
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.PromptOrder
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.chat.FakeCompanionPrefs
import org.xmyun.starmate.feature.chat.FakeCompanionStore
import org.xmyun.starmate.feature.chat.FakeDiag
import org.xmyun.starmate.feature.chat.FakeEngine
import org.xmyun.starmate.feature.chat.FakeMessageStore
import org.xmyun.starmate.feature.chat.FakeTexts
import org.xmyun.starmate.feature.chat.common.ChatBackgroundScope

class OnboardingPromptContributorTest {

    @Test
    fun interviewingAndOnboardingGivesPrompt() = runTest {
        val contributor = OnboardingPromptContributor(FakeCompanionPrefs(OnboardingState.INTERVIEWING), FakeTexts())
        assertEquals(PromptOrder.ONBOARDING, contributor.order)
        assertNotNull(contributor.stable(TurnPurpose.ONBOARDING))
        // 逐字稳定
        assertEquals(contributor.stable(TurnPurpose.ONBOARDING), contributor.stable(TurnPurpose.ONBOARDING))
    }

    @Test
    fun doneGivesNull() = runTest {
        val contributor = OnboardingPromptContributor(FakeCompanionPrefs(OnboardingState.DONE), FakeTexts())
        assertNull(contributor.stable(TurnPurpose.ONBOARDING))
    }

    @Test
    fun chatPurposeGivesNull() = runTest {
        val contributor = OnboardingPromptContributor(FakeCompanionPrefs(OnboardingState.INTERVIEWING), FakeTexts())
        assertNull(contributor.stable(TurnPurpose.CHAT))
        assertNull(contributor.dynamic(TurnPurpose.ONBOARDING, "hi"))
    }

    @Test
    fun toolOnlyOfferedForOnboarding() = runTest {
        val prefs = FakeCompanionPrefs(OnboardingState.INTERVIEWING)
        val texts = FakeTexts()
        val coordinator = InterviewCoordinator(
            prefs = prefs,
            messages = FakeMessageStore(),
            engine = { FakeEngine() },
            applier = ProfileApplier(FakeCompanionStore(), prefs, Optional.empty(), texts, FakeDiag()),
            texts = texts,
            background = ChatBackgroundScope(this),
            clock = Clock.systemUTC(),
            diag = FakeDiag(),
        )
        val tool = FinishOnboardingTool(coordinator = coordinator, texts = texts)
        val provider = OnboardingToolProvider { tool }
        assertEquals(listOf(FinishOnboardingTool.NAME), provider.tools(TurnPurpose.ONBOARDING).map { it.name })
        assertEquals(emptyList<Any>(), provider.tools(TurnPurpose.CHAT))
        assertEquals(emptyList<Any>(), provider.tools(TurnPurpose.PROACTIVE))
    }
}
