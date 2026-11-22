package org.xmyun.starmate.feature.proactive.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import org.xmyun.starmate.core.model.DataPortable
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.PromptContributor
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.ui.contract.AppNavigator
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.core.ui.contract.PermissionInvite
import org.xmyun.starmate.feature.proactive.NotificationPermissionInvite
import org.xmyun.starmate.feature.proactive.ProactiveDataPortable
import org.xmyun.starmate.feature.proactive.ProactiveFeatureEntry
import org.xmyun.starmate.feature.proactive.ProactivePromptContributor
import org.xmyun.starmate.feature.proactive.db.ProactiveDatabase
import org.xmyun.starmate.feature.proactive.db.ProactiveDatabaseHolder
import org.xmyun.starmate.feature.proactive.keepalive.KeepAliveController
import org.xmyun.starmate.feature.proactive.notify.AndroidProactiveNotifier
import org.xmyun.starmate.feature.proactive.notify.ProactiveNotifier
import org.xmyun.starmate.feature.proactive.platform.ConnectivityNetworkStatus
import org.xmyun.starmate.feature.proactive.platform.DefaultRandomSource
import org.xmyun.starmate.feature.proactive.platform.NetworkStatus
import org.xmyun.starmate.feature.proactive.platform.ProactiveTexts
import org.xmyun.starmate.feature.proactive.platform.RandomSource
import org.xmyun.starmate.feature.proactive.platform.ResourceProactiveTexts
import org.xmyun.starmate.feature.proactive.prefs.ProactivePrefs
import org.xmyun.starmate.feature.proactive.schedule.AlarmCareScheduler
import org.xmyun.starmate.feature.proactive.schedule.CareRescheduler
import org.xmyun.starmate.feature.proactive.schedule.CareScheduler
import org.xmyun.starmate.feature.proactive.schedule.CheckInScheduler
import org.xmyun.starmate.feature.proactive.schedule.WorkCheckInScheduler
import org.xmyun.starmate.feature.proactive.store.CareStore
import org.xmyun.starmate.feature.proactive.store.RoomCareStore
import org.xmyun.starmate.feature.proactive.store.RoomSendLogStore
import org.xmyun.starmate.feature.proactive.store.SendLogStore
import org.xmyun.starmate.feature.proactive.tools.ProactiveToolProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class ProactiveModule {

    @Binds @IntoSet
    abstract fun featureEntry(impl: ProactiveFeatureEntry): FeatureEntry

    @Binds @IntoSet
    abstract fun toolProvider(impl: ProactiveToolProvider): ToolProvider

    @Binds @IntoSet
    abstract fun promptContributor(impl: ProactivePromptContributor): PromptContributor

    @Binds @IntoSet
    abstract fun permissionInvite(impl: NotificationPermissionInvite): PermissionInvite

    @Binds @IntoSet
    abstract fun dataPortable(impl: ProactiveDataPortable): DataPortable

    @Binds
    abstract fun careStore(impl: RoomCareStore): CareStore

    @Binds
    abstract fun sendLogStore(impl: RoomSendLogStore): SendLogStore

    @Binds
    abstract fun careScheduler(impl: AlarmCareScheduler): CareScheduler

    @Binds
    abstract fun checkInScheduler(impl: WorkCheckInScheduler): CheckInScheduler

    @Binds
    abstract fun notifier(impl: AndroidProactiveNotifier): ProactiveNotifier

    @Binds
    abstract fun networkStatus(impl: ConnectivityNetworkStatus): NetworkStatus

    @Binds
    abstract fun randomSource(impl: DefaultRandomSource): RandomSource

    @Binds
    abstract fun texts(impl: ResourceProactiveTexts): ProactiveTexts

    companion object {
        @Provides
        @Singleton
        fun provideDatabaseHolder(@ApplicationContext context: Context): ProactiveDatabaseHolder =
            ProactiveDatabaseHolder(context.getDatabasePath(ProactiveDatabase.FILE_NAME)) {
                ProactiveDatabase.build(context, ProactiveDatabase.FILE_NAME)
            }

        // DataStore 包在 ProactivePrefs 里，不对外绑定裸的 DataStore<Preferences>
        @Provides
        @Singleton
        fun providePrefs(@ApplicationContext context: Context): ProactivePrefs =
            ProactivePrefs(
                PreferenceDataStoreFactory.create(
                    produceFile = { context.preferencesDataStoreFile(ProactivePrefs.FILE_NAME) },
                ),
            )
    }
}

/** 给 BroadcastReceiver 和 Service 取依赖用。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProactiveEntryPoint {
    fun rescheduler(): CareRescheduler
    fun keepAliveController(): KeepAliveController
    fun appNavigator(): AppNavigator
    fun diagLog(): DiagLog
}
