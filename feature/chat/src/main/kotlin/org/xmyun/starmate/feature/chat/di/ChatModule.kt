package org.xmyun.starmate.feature.chat.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import org.xmyun.starmate.core.model.DataPortable
import org.xmyun.starmate.core.model.PromptContributor
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.model.TurnListener
import org.xmyun.starmate.core.ui.contract.AttachmentRenderer
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.feature.chat.ChatFeatureEntry
import org.xmyun.starmate.feature.chat.chat.QuoteRenderer
import org.xmyun.starmate.feature.chat.common.AvatarPortable
import org.xmyun.starmate.feature.chat.common.ChatPrefs
import org.xmyun.starmate.feature.chat.common.DataStoreChatPrefs
import org.xmyun.starmate.feature.chat.interview.InterviewTexts
import org.xmyun.starmate.feature.chat.interview.InterviewTurnListener
import org.xmyun.starmate.feature.chat.interview.OnboardingPromptContributor
import org.xmyun.starmate.feature.chat.interview.OnboardingSummaryRenderer
import org.xmyun.starmate.feature.chat.interview.OnboardingToolProvider
import org.xmyun.starmate.feature.chat.interview.ResourceInterviewTexts

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatModule {

    @Binds @IntoSet
    abstract fun featureEntry(impl: ChatFeatureEntry): FeatureEntry

    @Binds @IntoSet
    abstract fun quoteRenderer(impl: QuoteRenderer): AttachmentRenderer

    @Binds @IntoSet
    abstract fun summaryRenderer(impl: OnboardingSummaryRenderer): AttachmentRenderer

    @Binds @IntoSet
    abstract fun promptContributor(impl: OnboardingPromptContributor): PromptContributor

    @Binds @IntoSet
    abstract fun toolProvider(impl: OnboardingToolProvider): ToolProvider

    @Binds @IntoSet
    abstract fun turnListener(impl: InterviewTurnListener): TurnListener

    @Binds @IntoSet
    abstract fun avatarPortable(impl: AvatarPortable): DataPortable

    @Binds
    abstract fun chatPrefs(impl: DataStoreChatPrefs): ChatPrefs

    @Binds
    abstract fun interviewTexts(impl: ResourceInterviewTexts): InterviewTexts
}
