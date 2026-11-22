package org.xmyun.starmate.feature.mcp.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
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
import org.xmyun.starmate.feature.mcp.AndroidMcpText
import org.xmyun.starmate.feature.mcp.DefaultMcpConnectionFactory
import org.xmyun.starmate.feature.mcp.DefaultMcpEndpoints
import org.xmyun.starmate.feature.mcp.McpConnectionFactory
import org.xmyun.starmate.feature.mcp.McpEndpoints
import org.xmyun.starmate.feature.mcp.McpFeatureEntry
import org.xmyun.starmate.feature.mcp.McpPrefs
import org.xmyun.starmate.feature.mcp.McpText
import org.xmyun.starmate.feature.mcp.McpToolProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class McpModule {

    @Binds @IntoSet abstract fun toolProvider(impl: McpToolProvider): ToolProvider

    @Binds @IntoSet abstract fun entry(impl: McpFeatureEntry): FeatureEntry

    @Binds abstract fun endpoints(impl: DefaultMcpEndpoints): McpEndpoints

    @Binds abstract fun connectionFactory(impl: DefaultMcpConnectionFactory): McpConnectionFactory

    @Binds abstract fun text(impl: AndroidMcpText): McpText

    companion object {
        @Provides
        @Singleton
        fun prefs(@ApplicationContext context: Context): McpPrefs = McpPrefs(
            PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile(McpPrefs.FILE_NAME) }),
        )
    }
}
