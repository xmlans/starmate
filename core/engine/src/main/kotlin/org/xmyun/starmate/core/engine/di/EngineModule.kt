package org.xmyun.starmate.core.engine.di

import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import org.xmyun.starmate.core.engine.DataStoreToolConsentStore
import org.xmyun.starmate.core.engine.DefaultChatDraftBus
import org.xmyun.starmate.core.engine.DefaultConfirmationBroker
import org.xmyun.starmate.core.engine.DefaultConversationEngine
import org.xmyun.starmate.core.model.ToolConsentStore
import org.xmyun.starmate.core.engine.guidelines.BaseGuidelines
import org.xmyun.starmate.core.engine.guidelines.GuidelinesSource
import org.xmyun.starmate.core.model.AmbientSource
import org.xmyun.starmate.core.model.ChatDraftBus
import org.xmyun.starmate.core.model.ConfirmationBroker
import org.xmyun.starmate.core.model.ConversationEngine
import org.xmyun.starmate.core.model.DataPortable
import org.xmyun.starmate.core.model.DaySummarySource
import org.xmyun.starmate.core.model.MemoryWriter
import org.xmyun.starmate.core.model.PromptContributor
import org.xmyun.starmate.core.model.ReplyPostProcessor
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.model.TurnListener

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {

    // 没有任何功能模块贡献时这些集合也能注入
    @Multibinds abstract fun toolProviders(): Set<ToolProvider>

    @Multibinds abstract fun promptContributors(): Set<PromptContributor>

    @Multibinds abstract fun ambientSources(): Set<AmbientSource>

    @Multibinds abstract fun turnListeners(): Set<TurnListener>

    @Multibinds abstract fun replyPostProcessors(): Set<ReplyPostProcessor>

    @Multibinds abstract fun dataPortables(): Set<DataPortable>

    // 可选依赖，注入类型为 java.util.Optional<T>
    @BindsOptionalOf abstract fun memoryWriter(): MemoryWriter

    @BindsOptionalOf abstract fun daySummarySource(): DaySummarySource

    @Binds abstract fun conversationEngine(impl: DefaultConversationEngine): ConversationEngine

    @Binds abstract fun confirmationBroker(impl: DefaultConfirmationBroker): ConfirmationBroker

    @Binds abstract fun chatDraftBus(impl: DefaultChatDraftBus): ChatDraftBus

    @Binds abstract fun toolConsentStore(impl: DataStoreToolConsentStore): ToolConsentStore

    @Binds abstract fun guidelinesSource(impl: BaseGuidelines): GuidelinesSource
}
