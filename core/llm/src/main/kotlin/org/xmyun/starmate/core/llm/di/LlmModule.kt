package org.xmyun.starmate.core.llm.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import org.xmyun.starmate.core.llm.provider.DataStoreProviderStore
import org.xmyun.starmate.core.llm.provider.DefaultLlmClientFactory
import org.xmyun.starmate.core.llm.provider.DefaultModelRouter
import org.xmyun.starmate.core.llm.provider.LlmClientFactory
import org.xmyun.starmate.core.model.ModelRouter
import org.xmyun.starmate.core.model.ProviderStore

/** 本模块专用的 DataStore（文件名 providers）。加限定符，避免和别的模块的 DataStore<Preferences> 撞绑定。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProvidersDataStore

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmModule {

    @Binds
    abstract fun bindProviderStore(impl: DataStoreProviderStore): ProviderStore

    @Binds
    abstract fun bindModelRouter(impl: DefaultModelRouter): ModelRouter

    @Binds
    abstract fun bindClientFactory(impl: DefaultLlmClientFactory): LlmClientFactory

    companion object {
        @Provides
        @Singleton
        @ProvidersDataStore
        fun provideProvidersDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("providers") })
    }
}
