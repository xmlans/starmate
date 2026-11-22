package org.xmyun.starmate.feature.diary.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.feature.diary.write.DiaryFrequency

/** 日记的偏好：频率（默认偶尔）、用哪个模型写（默认 CHAT，保持她的口吻）。 */
class DiaryPrefs(private val store: DataStore<Preferences>) {

    val frequency: Flow<DiaryFrequency> = store.data.map { prefs ->
        prefs[FREQUENCY]?.let { runCatching { DiaryFrequency.valueOf(it) }.getOrNull() } ?: DiaryFrequency.SOMETIMES
    }

    suspend fun setFrequency(value: DiaryFrequency) {
        store.edit { it[FREQUENCY] = value.name }
    }

    val role: Flow<ModelRole> = store.data.map { prefs ->
        if (prefs[ROLE] == ModelRole.BACKGROUND.name) ModelRole.BACKGROUND else ModelRole.CHAT
    }

    /** 只接受 CHAT 和 BACKGROUND。 */
    suspend fun setRole(value: ModelRole) {
        store.edit { it[ROLE] = if (value == ModelRole.BACKGROUND) ModelRole.BACKGROUND.name else ModelRole.CHAT.name }
    }

    companion object {
        const val FILE_NAME = "diary_prefs"
        private val FREQUENCY = stringPreferencesKey("frequency")
        private val ROLE = stringPreferencesKey("role")
    }
}
