package org.xmyun.starmate.feature.memory.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import org.xmyun.starmate.core.model.DataPortable
import org.xmyun.starmate.core.model.DaySummarySource
import org.xmyun.starmate.core.model.MemoryWriter
import org.xmyun.starmate.core.model.PromptContributor
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.model.TurnListener
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.feature.memory.DefaultDaySummarySource
import org.xmyun.starmate.feature.memory.DefaultMemoryWriter
import org.xmyun.starmate.feature.memory.MemoryDataPortable
import org.xmyun.starmate.feature.memory.MemoryFeatureEntry
import org.xmyun.starmate.feature.memory.MemoryTurnListener
import org.xmyun.starmate.feature.memory.db.MemoryDatabase
import org.xmyun.starmate.feature.memory.db.MemoryDatabaseHolder
import org.xmyun.starmate.feature.memory.inject.MemoryPromptContributor
import org.xmyun.starmate.feature.memory.model.MemorySettings
import org.xmyun.starmate.feature.memory.prompt.PromptSource
import org.xmyun.starmate.feature.memory.prompt.ResourcePromptSource
import org.xmyun.starmate.feature.memory.tools.MemoryToolProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class MemoryModule {

    @Binds
    abstract fun promptSource(impl: ResourcePromptSource): PromptSource

    @Binds
    abstract fun memoryWriter(impl: DefaultMemoryWriter): MemoryWriter

    @Binds
    abstract fun daySummarySource(impl: DefaultDaySummarySource): DaySummarySource

    @Binds @IntoSet
    abstract fun promptContributor(impl: MemoryPromptContributor): PromptContributor

    @Binds @IntoSet
    abstract fun toolProvider(impl: MemoryToolProvider): ToolProvider

    @Binds @IntoSet
    abstract fun turnListener(impl: MemoryTurnListener): TurnListener

    @Binds @IntoSet
    abstract fun dataPortable(impl: MemoryDataPortable): DataPortable

    @Binds @IntoSet
    abstract fun featureEntry(impl: MemoryFeatureEntry): FeatureEntry

    companion object {
        @Provides
        @Singleton
        fun settings(): MemorySettings = MemorySettings()

        @Provides
        @Singleton
        fun databaseHolder(@ApplicationContext context: Context): MemoryDatabaseHolder =
            MemoryDatabaseHolder(context.getDatabasePath(MemoryDatabase.FILE_NAME)) {
                MemoryDatabase.build(context, MemoryDatabase.FILE_NAME)
            }
    }
}
