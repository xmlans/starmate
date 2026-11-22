package org.xmyun.starmate.core.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.xmyun.starmate.core.model.ToolConsentStore

/** [ToolConsentStore] 的实现，DataStore 文件 tool_consent。 */
@Singleton
class DataStoreToolConsentStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : ToolConsentStore {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile(FILE_NAME) }),
    )

    override val remembered: Flow<Set<String>> =
        dataStore.data.map { it[KEY] ?: emptySet() }

    override suspend fun isRemembered(toolName: String): Boolean =
        toolName in remembered.first()

    override suspend fun remember(toolName: String) {
        dataStore.edit { it[KEY] = (it[KEY] ?: emptySet()) + toolName }
    }

    override suspend fun forget(toolName: String) {
        dataStore.edit { it[KEY] = (it[KEY] ?: emptySet()) - toolName }
    }

    companion object {
        const val FILE_NAME = "tool_consent"
        private val KEY = stringSetPreferencesKey("remembered_tools")
    }
}
