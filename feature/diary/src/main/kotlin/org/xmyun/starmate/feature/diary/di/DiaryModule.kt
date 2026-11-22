package org.xmyun.starmate.feature.diary.di

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
import org.xmyun.starmate.core.model.DataPortable
import org.xmyun.starmate.core.ui.contract.FeatureEntry
import org.xmyun.starmate.feature.diary.DiaryDataPortable
import org.xmyun.starmate.feature.diary.DiaryFeatureEntry
import org.xmyun.starmate.feature.diary.db.DiaryDatabase
import org.xmyun.starmate.feature.diary.db.DiaryDatabaseHolder
import org.xmyun.starmate.feature.diary.prefs.DiaryPrefs
import org.xmyun.starmate.feature.diary.write.DiaryPromptSource
import org.xmyun.starmate.feature.diary.write.ResourceDiaryPromptSource

@Module
@InstallIn(SingletonComponent::class)
abstract class DiaryModule {

    @Binds
    abstract fun promptSource(impl: ResourceDiaryPromptSource): DiaryPromptSource

    @Binds @IntoSet
    abstract fun dataPortable(impl: DiaryDataPortable): DataPortable

    @Binds @IntoSet
    abstract fun featureEntry(impl: DiaryFeatureEntry): FeatureEntry

    companion object {
        @Provides
        @Singleton
        fun databaseHolder(@ApplicationContext context: Context): DiaryDatabaseHolder =
            DiaryDatabaseHolder(context.getDatabasePath(DiaryDatabase.FILE_NAME)) {
                DiaryDatabase.build(context, DiaryDatabase.FILE_NAME)
            }

        // DataStore 包在 DiaryPrefs 里，不对外绑定通用的 DataStore<Preferences>
        @Provides
        @Singleton
        fun prefs(@ApplicationContext context: Context): DiaryPrefs =
            DiaryPrefs(PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile(DiaryPrefs.FILE_NAME) }))
    }
}
