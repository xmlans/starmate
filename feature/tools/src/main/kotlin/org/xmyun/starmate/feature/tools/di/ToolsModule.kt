package org.xmyun.starmate.feature.tools.di

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
import java.time.Clock
import javax.inject.Singleton
import org.xmyun.starmate.core.model.AmbientSource
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.core.ui.contract.PermissionInvite
import org.xmyun.starmate.feature.tools.ToolsFeatureEntry
import org.xmyun.starmate.feature.tools.alarm.AlarmGateway
import org.xmyun.starmate.feature.tools.alarm.AlarmTools
import org.xmyun.starmate.feature.tools.alarm.AndroidAlarmGateway
import org.xmyun.starmate.feature.tools.calendar.AndroidCalendarGateway
import org.xmyun.starmate.feature.tools.calendar.CalendarAmbient
import org.xmyun.starmate.feature.tools.calendar.CalendarGateway
import org.xmyun.starmate.feature.tools.calendar.CalendarTools
import org.xmyun.starmate.feature.tools.common.AndroidGroupPermissions
import org.xmyun.starmate.feature.tools.common.AndroidToolText
import org.xmyun.starmate.feature.tools.common.GroupPermissions
import org.xmyun.starmate.feature.tools.common.ToolText
import org.xmyun.starmate.feature.tools.common.ToolsPrefs
import org.xmyun.starmate.feature.tools.contacts.AndroidContactsGateway
import org.xmyun.starmate.feature.tools.contacts.ContactsGateway
import org.xmyun.starmate.feature.tools.contacts.ContactsTools
import org.xmyun.starmate.feature.tools.db.StepSampleDao
import org.xmyun.starmate.feature.tools.db.ToolsDatabase
import org.xmyun.starmate.feature.tools.db.TrackDao
import org.xmyun.starmate.feature.tools.health.AndroidStepSensorReader
import org.xmyun.starmate.feature.tools.health.HealthAmbient
import org.xmyun.starmate.feature.tools.health.HealthConnectGateway
import org.xmyun.starmate.feature.tools.health.HealthGateway
import org.xmyun.starmate.feature.tools.health.HealthTools
import org.xmyun.starmate.feature.tools.health.StepSensorReader
import org.xmyun.starmate.feature.tools.location.AndroidLocationGateway
import org.xmyun.starmate.feature.tools.location.LocationGateway
import org.xmyun.starmate.feature.tools.location.LocationTools
import org.xmyun.starmate.feature.tools.location.WeatherAmbient
import org.xmyun.starmate.feature.tools.location.WeatherClient
import org.xmyun.starmate.feature.tools.music.MusicAmbient
import org.xmyun.starmate.feature.tools.music.MusicTools
import org.xmyun.starmate.feature.tools.ui.CalendarInvite
import org.xmyun.starmate.feature.tools.ui.HealthInvite
import org.xmyun.starmate.feature.tools.ui.LocationInvite
import org.xmyun.starmate.feature.tools.ui.MusicInvite

@Module
@InstallIn(SingletonComponent::class)
abstract class ToolsModule {

    @Binds @IntoSet abstract fun calendarTools(impl: CalendarTools): ToolProvider
    @Binds @IntoSet abstract fun alarmTools(impl: AlarmTools): ToolProvider
    @Binds @IntoSet abstract fun contactsTools(impl: ContactsTools): ToolProvider
    @Binds @IntoSet abstract fun locationTools(impl: LocationTools): ToolProvider
    @Binds @IntoSet abstract fun healthTools(impl: HealthTools): ToolProvider
    @Binds @IntoSet abstract fun musicTools(impl: MusicTools): ToolProvider

    @Binds @IntoSet abstract fun calendarAmbient(impl: CalendarAmbient): AmbientSource
    @Binds @IntoSet abstract fun weatherAmbient(impl: WeatherAmbient): AmbientSource
    @Binds @IntoSet abstract fun healthAmbient(impl: HealthAmbient): AmbientSource
    @Binds @IntoSet abstract fun musicAmbient(impl: MusicAmbient): AmbientSource

    @Binds @IntoSet abstract fun locationInvite(impl: LocationInvite): PermissionInvite
    @Binds @IntoSet abstract fun calendarInvite(impl: CalendarInvite): PermissionInvite
    @Binds @IntoSet abstract fun healthInvite(impl: HealthInvite): PermissionInvite
    @Binds @IntoSet abstract fun musicInvite(impl: MusicInvite): PermissionInvite

    @Binds @IntoSet abstract fun entry(impl: ToolsFeatureEntry): FeatureEntry

    @Binds abstract fun calendarGateway(impl: AndroidCalendarGateway): CalendarGateway
    @Binds abstract fun alarmGateway(impl: AndroidAlarmGateway): AlarmGateway
    @Binds abstract fun contactsGateway(impl: AndroidContactsGateway): ContactsGateway
    @Binds abstract fun locationGateway(impl: AndroidLocationGateway): LocationGateway
    @Binds abstract fun healthGateway(impl: HealthConnectGateway): HealthGateway
    @Binds abstract fun stepSensor(impl: AndroidStepSensorReader): StepSensorReader
    @Binds abstract fun groupPermissions(impl: AndroidGroupPermissions): GroupPermissions
    @Binds abstract fun toolText(impl: AndroidToolText): ToolText

    companion object {
        @Provides
        @Singleton
        fun prefs(@ApplicationContext context: Context): ToolsPrefs = ToolsPrefs(
            PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile(ToolsPrefs.FILE_NAME) }),
        )

        @Provides
        @Singleton
        fun database(@ApplicationContext context: Context): ToolsDatabase = ToolsDatabase.build(context)

        @Provides
        fun trackDao(db: ToolsDatabase): TrackDao = db.trackDao()

        @Provides
        fun stepSampleDao(db: ToolsDatabase): StepSampleDao = db.stepSampleDao()

        @Provides
        @Singleton
        fun weatherClient(clock: Clock): WeatherClient = WeatherClient(WeatherClient.createHttpClient(), clock)
    }
}
