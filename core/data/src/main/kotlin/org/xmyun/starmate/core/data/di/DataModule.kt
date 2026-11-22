package org.xmyun.starmate.core.data.di

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
import dagger.multibindings.IntoSet
import java.time.Clock
import javax.inject.Singleton
import org.xmyun.starmate.core.data.db.ChatDatabase
import org.xmyun.starmate.core.data.db.ChatDatabaseHolder
import org.xmyun.starmate.core.data.diag.RingDiagLog
import org.xmyun.starmate.core.data.portable.ChatDataPortable
import org.xmyun.starmate.core.data.portable.CompanionPrefsPortable
import org.xmyun.starmate.core.data.portable.DataArchive
import org.xmyun.starmate.core.data.prefs.DataStoreCompanionPrefs
import org.xmyun.starmate.core.data.secret.DataStoreSecretStore
import org.xmyun.starmate.core.data.secret.KeystoreSecretCipher
import org.xmyun.starmate.core.data.secret.SecretCipher
import org.xmyun.starmate.core.data.store.RoomCompanionStore
import org.xmyun.starmate.core.data.store.RoomMessageStore
import org.xmyun.starmate.core.data.store.RoomUsageLedger
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.CompanionStore
import org.xmyun.starmate.core.model.DataArchiver
import org.xmyun.starmate.core.model.DataPortable
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.MessageStore
import org.xmyun.starmate.core.model.SecretStore
import org.xmyun.starmate.core.model.UsageLedger

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun bindSecretCipher(impl: KeystoreSecretCipher): SecretCipher

    @Binds
    abstract fun bindSecretStore(impl: DataStoreSecretStore): SecretStore

    @Binds
    abstract fun bindCompanionPrefs(impl: DataStoreCompanionPrefs): CompanionPrefs

    @Binds
    abstract fun bindMessageStore(impl: RoomMessageStore): MessageStore

    @Binds
    abstract fun bindCompanionStore(impl: RoomCompanionStore): CompanionStore

    @Binds
    abstract fun bindUsageLedger(impl: RoomUsageLedger): UsageLedger

    @Binds
    abstract fun bindDataArchiver(impl: DataArchive): DataArchiver

    @Binds
    abstract fun bindDiagLog(impl: RingDiagLog): DiagLog

    @Binds
    @IntoSet
    abstract fun bindChatDataPortable(impl: ChatDataPortable): DataPortable

    @Binds
    @IntoSet
    abstract fun bindCompanionPrefsPortable(impl: CompanionPrefsPortable): DataPortable

    companion object {

        @Provides
        @Singleton
        fun provideClock(): Clock = Clock.systemDefaultZone()

        @Provides
        @Singleton
        @SecretsDataStore
        fun provideSecretsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                produceFile = { context.preferencesDataStoreFile(DataStoreSecretStore.DATASTORE_NAME) },
            )

        @Provides
        @Singleton
        @CompanionPrefsDataStore
        fun provideCompanionPrefsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                produceFile = { context.preferencesDataStoreFile(DataStoreCompanionPrefs.DATASTORE_NAME) },
            )

        @Provides
        @Singleton
        fun provideChatDatabaseHolder(@ApplicationContext context: Context): ChatDatabaseHolder =
            ChatDatabaseHolder(context.getDatabasePath(ChatDatabase.FILE_NAME)) {
                ChatDatabase.build(context, ChatDatabase.FILE_NAME)
            }
    }
}
