package org.xmyun.starmate.feature.voice.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 她发语音的频率。 */
enum class VoiceFrequency { NEVER, SOMETIMES, OFTEN, ALWAYS }

/** 语音模块的偏好，文件 voice_prefs。DataStore 由本类自己创建，不对外绑定。 */
@Singleton
class VoicePrefs(private val store: DataStore<Preferences>) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile(FILE_NAME) },
        ),
    )

    val frequency: Flow<VoiceFrequency> = store.data.map { prefs ->
        VoiceFrequency.entries.firstOrNull { it.name == prefs[KEY_FREQUENCY] } ?: DEFAULT_FREQUENCY
    }

    val dailyLimit: Flow<Int> = store.data.map { it[KEY_DAILY_LIMIT]?.coerceAtLeast(0) ?: DEFAULT_DAILY_LIMIT }

    val systemFallback: Flow<Boolean> = store.data.map { it[KEY_SYSTEM_FALLBACK] ?: false }

    suspend fun setFrequency(value: VoiceFrequency) {
        store.edit { it[KEY_FREQUENCY] = value.name }
    }

    suspend fun setDailyLimit(value: Int) {
        store.edit { it[KEY_DAILY_LIMIT] = value.coerceAtLeast(0) }
    }

    suspend fun setSystemFallback(value: Boolean) {
        store.edit { it[KEY_SYSTEM_FALLBACK] = value }
    }

    /** 今天已经发出的语音条数。 */
    suspend fun usedToday(today: LocalDate): Int {
        val prefs = store.data.first()
        return if (prefs[KEY_COUNT_DATE] == today.toString()) prefs[KEY_COUNT] ?: 0 else 0
    }

    suspend fun remainingToday(today: LocalDate): Int {
        val limit = dailyLimit.first()
        return (limit - usedToday(today)).coerceAtLeast(0)
    }

    /** 申请 requested 条今天的额度，返回实际批准的条数（原子操作）。 */
    suspend fun consume(requested: Int, today: LocalDate): Int {
        if (requested <= 0) return 0
        var granted = 0
        store.edit { prefs ->
            val day = today.toString()
            val used = if (prefs[KEY_COUNT_DATE] == day) prefs[KEY_COUNT] ?: 0 else 0
            val limit = prefs[KEY_DAILY_LIMIT]?.coerceAtLeast(0) ?: DEFAULT_DAILY_LIMIT
            granted = requested.coerceAtMost((limit - used).coerceAtLeast(0))
            prefs[KEY_COUNT_DATE] = day
            prefs[KEY_COUNT] = used + granted
        }
        return granted
    }

    companion object {
        const val FILE_NAME = "voice_prefs"
        const val DEFAULT_DAILY_LIMIT = 30
        val DEFAULT_FREQUENCY = VoiceFrequency.SOMETIMES
        val DAILY_LIMIT_CHOICES = listOf(5, 10, 20, 30, 50, 100)

        private val KEY_FREQUENCY = stringPreferencesKey("frequency")
        private val KEY_DAILY_LIMIT = intPreferencesKey("daily_limit")
        private val KEY_SYSTEM_FALLBACK = booleanPreferencesKey("system_fallback")
        private val KEY_COUNT_DATE = stringPreferencesKey("count_date")
        private val KEY_COUNT = intPreferencesKey("count")
    }
}
