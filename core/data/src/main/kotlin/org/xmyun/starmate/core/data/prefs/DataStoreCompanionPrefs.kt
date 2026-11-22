package org.xmyun.starmate.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.xmyun.starmate.core.data.di.CompanionPrefsDataStore
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.QuietHours
import org.xmyun.starmate.core.model.TurnPurpose

/** 共享偏好，DataStore 名 `companion_prefs`。 */
@Singleton
class DataStoreCompanionPrefs @Inject constructor(
    @CompanionPrefsDataStore private val dataStore: DataStore<Preferences>,
) : CompanionPrefs {

    private val prefs: Flow<Preferences> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    private fun <T> read(transform: (Preferences) -> T): Flow<T> = prefs.map(transform).distinctUntilChanged()

    override val adultConfirmed: Flow<Boolean> = read { it[ADULT_CONFIRMED] ?: false }

    override suspend fun setAdultConfirmed(value: Boolean) {
        dataStore.edit { it[ADULT_CONFIRMED] = value }
    }

    override val onboardingState: Flow<OnboardingState> = read { p ->
        p[ONBOARDING_STATE]?.let { name -> OnboardingState.entries.firstOrNull { it.name == name } }
            ?: OnboardingState.NOT_STARTED
    }

    override suspend fun setOnboardingState(state: OnboardingState) {
        dataStore.edit { it[ONBOARDING_STATE] = state.name }
    }

    override val pendingOnboardingProfile: Flow<String?> = read { it[PENDING_PROFILE] }

    override suspend fun setPendingOnboardingProfile(json: String?) {
        dataStore.edit { if (json == null) it.remove(PENDING_PROFILE) else it[PENDING_PROFILE] = json }
    }

    override val quietHours: Flow<QuietHours> = read { p ->
        val start = p[QUIET_START]
        val end = p[QUIET_END]
        if (start != null && end != null && start in MINUTE_RANGE && end in MINUTE_RANGE) {
            QuietHours(start, end)
        } else {
            QuietHours.DEFAULT
        }
    }

    override suspend fun setQuietHours(value: QuietHours) {
        require(value.startMinute in MINUTE_RANGE && value.endMinute in MINUTE_RANGE) {
            "quiet hours out of range"
        }
        dataStore.edit {
            it[QUIET_START] = value.startMinute
            it[QUIET_END] = value.endMinute
        }
    }

    override val clinginess: Flow<Clinginess> = read { p ->
        p[CLINGINESS]?.let { name -> Clinginess.entries.firstOrNull { it.name == name } } ?: Clinginess.MEDIUM
    }

    override suspend fun setClinginess(value: Clinginess) {
        dataStore.edit { it[CLINGINESS] = value.name }
    }

    override fun dailyBudget(purpose: TurnPurpose): Flow<Int> =
        read { it[budgetKey(purpose)] ?: defaultDailyBudget(purpose) }

    override suspend fun setDailyBudget(purpose: TurnPurpose, tokens: Int) {
        dataStore.edit { it[budgetKey(purpose)] = tokens.coerceAtLeast(0) }
    }

    override val historyTokenBudget: Flow<Int> = read { it[HISTORY_BUDGET] ?: DEFAULT_HISTORY_TOKEN_BUDGET }

    override suspend fun setHistoryTokenBudget(tokens: Int) {
        dataStore.edit { it[HISTORY_BUDGET] = tokens.coerceAtLeast(0) }
    }

    companion object {
        const val DATASTORE_NAME = "companion_prefs"
        const val DEFAULT_HISTORY_TOKEN_BUDGET = 8000

        fun defaultDailyBudget(purpose: TurnPurpose): Int = when (purpose) {
            TurnPurpose.MEMORY -> 80_000
            TurnPurpose.PROACTIVE -> 20_000
            TurnPurpose.DIARY -> 10_000
            else -> Int.MAX_VALUE
        }

        private val MINUTE_RANGE = 0..1439

        private val ADULT_CONFIRMED = booleanPreferencesKey("adult_confirmed")
        private val ONBOARDING_STATE = stringPreferencesKey("onboarding_state")
        private val PENDING_PROFILE = stringPreferencesKey("pending_onboarding_profile")
        private val QUIET_START = intPreferencesKey("quiet_start_minute")
        private val QUIET_END = intPreferencesKey("quiet_end_minute")
        private val CLINGINESS = stringPreferencesKey("clinginess")
        private val HISTORY_BUDGET = intPreferencesKey("history_token_budget")

        private fun budgetKey(purpose: TurnPurpose) = intPreferencesKey("daily_budget_${purpose.name}")
    }
}
