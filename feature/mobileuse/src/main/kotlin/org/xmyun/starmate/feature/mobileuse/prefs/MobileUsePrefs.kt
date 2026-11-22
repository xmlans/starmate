package org.xmyun.starmate.feature.mobileuse.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.xmyun.starmate.feature.mobileuse.agent.TaskLimits

/** 手机操作的偏好（mobileuse_prefs）。默认关闭、未读风险说明、白名单为空。 */
@Singleton
class MobileUsePrefs(private val store: DataStore<Preferences>) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile(FILE_NAME) }),
    )

    val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: false }
    val riskAccepted: Flow<Boolean> = store.data.map { it[RISK_ACCEPTED] ?: false }
    val allowlist: Flow<Set<String>> = store.data.map { it[ALLOWLIST] ?: emptySet() }
    val limits: Flow<TaskLimits> = store.data.map { p ->
        TaskLimits(
            maxSteps = p[MAX_STEPS] ?: TaskLimits.DEFAULT_STEPS,
            maxDuration = Duration.ofMinutes((p[MAX_MINUTES] ?: TaskLimits.DEFAULT_DURATION.toMinutes().toInt()).toLong()),
            maxTokens = p[MAX_TOKENS] ?: TaskLimits.DEFAULT_TOKENS,
        ).sanitized()
    }

    suspend fun setEnabled(value: Boolean) {
        store.edit { it[ENABLED] = value }
    }

    /** 接受风险说明。撤销时同时关闭功能。 */
    suspend fun setRiskAccepted(value: Boolean) {
        store.edit {
            it[RISK_ACCEPTED] = value
            if (!value) it[ENABLED] = false
        }
    }

    suspend fun setAllowed(packageName: String, allowed: Boolean) {
        store.edit {
            val current = it[ALLOWLIST] ?: emptySet()
            it[ALLOWLIST] = if (allowed) current + packageName else current - packageName
        }
    }

    suspend fun setLimits(limits: TaskLimits) {
        val l = limits.sanitized()
        store.edit {
            it[MAX_STEPS] = l.maxSteps
            it[MAX_MINUTES] = l.maxDuration.toMinutes().toInt()
            it[MAX_TOKENS] = l.maxTokens
        }
    }

    suspend fun currentAllowlist(): Set<String> = allowlist.first()

    companion object {
        const val FILE_NAME = "mobileuse_prefs"
        private val ENABLED = booleanPreferencesKey("enabled")
        private val RISK_ACCEPTED = booleanPreferencesKey("risk_accepted")
        private val ALLOWLIST = stringSetPreferencesKey("allowlist")
        private val MAX_STEPS = intPreferencesKey("max_steps")
        private val MAX_MINUTES = intPreferencesKey("max_minutes")
        private val MAX_TOKENS = intPreferencesKey("max_tokens")
    }
}
