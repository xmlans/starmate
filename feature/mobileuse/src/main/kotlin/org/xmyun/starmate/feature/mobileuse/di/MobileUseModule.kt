package org.xmyun.starmate.feature.mobileuse.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.feature.mobileuse.MobileUseFeatureEntry
import org.xmyun.starmate.feature.mobileuse.agent.PromptSource
import org.xmyun.starmate.feature.mobileuse.agent.RawPromptSource
import org.xmyun.starmate.feature.mobileuse.log.ActionLogDatabase
import org.xmyun.starmate.feature.mobileuse.service.DefaultRunnerFactory
import org.xmyun.starmate.feature.mobileuse.tool.AndroidAvailabilitySource
import org.xmyun.starmate.feature.mobileuse.tool.AvailabilitySource
import org.xmyun.starmate.feature.mobileuse.tool.MobileUseToolProvider
import org.xmyun.starmate.feature.mobileuse.tool.PhoneTaskRunnerFactory

@Module
@InstallIn(SingletonComponent::class)
abstract class MobileUseModule {

    @Binds
    @IntoSet
    abstract fun toolProvider(impl: MobileUseToolProvider): ToolProvider

    @Binds
    @IntoSet
    abstract fun featureEntry(impl: MobileUseFeatureEntry): FeatureEntry

    @Binds
    abstract fun availabilitySource(impl: AndroidAvailabilitySource): AvailabilitySource

    @Binds
    abstract fun runnerFactory(impl: DefaultRunnerFactory): PhoneTaskRunnerFactory

    @Binds
    abstract fun promptSource(impl: RawPromptSource): PromptSource

    companion object {
        @Provides
        @Singleton
        fun actionLogDatabase(@ApplicationContext context: Context): ActionLogDatabase =
            ActionLogDatabase.build(context)
    }
}
