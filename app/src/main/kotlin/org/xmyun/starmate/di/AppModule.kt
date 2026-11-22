package org.xmyun.starmate.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.xmyun.starmate.BuildConfig
import org.xmyun.starmate.DefaultAppNavigator
import org.xmyun.starmate.ProcessAppState
import org.xmyun.starmate.core.model.AppEdition
import org.xmyun.starmate.core.model.AppState
import org.xmyun.starmate.core.ui.contract.AppNavigator

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun appNavigator(impl: DefaultAppNavigator): AppNavigator

    @Binds
    abstract fun appState(impl: ProcessAppState): AppState

    companion object {
        /** 当前产品变体的能力开关，取值见 app/build.gradle.kts 的 productFlavors。 */
        @Provides
        fun appEdition(): AppEdition = AppEdition(
            id = BuildConfig.EDITION,
            versionName = BuildConfig.VERSION_NAME,
            keepAlive = BuildConfig.KEEP_ALIVE,
            batteryExemptionRequest = BuildConfig.BATTERY_EXEMPTION_REQUEST,
            contentReports = BuildConfig.CONTENT_REPORTS,
            contactEmail = BuildConfig.CONTACT_EMAIL,
        )
    }
}
