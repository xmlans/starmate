package org.xmyun.starmate.feature.chat.common

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 本模块自己的偏好（chat_prefs）。 */
interface ChatPrefs {
    /** 引导第 6 步的权限邀请页是否已经出现过。 */
    val invitesShown: Flow<Boolean>
    suspend fun setInvitesShown(value: Boolean)
}

@Singleton
class DataStoreChatPrefs(private val store: DataStore<Preferences>) : ChatPrefs {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile(FILE_NAME) }),
    )

    override val invitesShown: Flow<Boolean> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[KEY_INVITES_SHOWN] ?: false }
        .distinctUntilChanged()

    override suspend fun setInvitesShown(value: Boolean) {
        store.edit { it[KEY_INVITES_SHOWN] = value }
    }

    private companion object {
        const val FILE_NAME = "chat_prefs"
        val KEY_INVITES_SHOWN = booleanPreferencesKey("invites_shown")
    }
}
