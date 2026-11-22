package org.xmyun.starmate.core.data.prefs

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.data.newPrefsDataStore
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.QuietHours
import org.xmyun.starmate.core.model.TurnPurpose

@RunWith(RobolectricTestRunner::class)
class DataStoreCompanionPrefsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var prefs: DataStoreCompanionPrefs

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        prefs = DataStoreCompanionPrefs(newPrefsDataStore(scope, File(tmp.root, "companion_prefs.preferences_pb")))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun defaults() = runTest {
        assertFalse(prefs.adultConfirmed.first())
        assertEquals(OnboardingState.NOT_STARTED, prefs.onboardingState.first())
        assertNull(prefs.pendingOnboardingProfile.first())
        assertEquals(QuietHours.DEFAULT, prefs.quietHours.first())
        assertEquals(Clinginess.MEDIUM, prefs.clinginess.first())
        assertEquals(80_000, prefs.dailyBudget(TurnPurpose.MEMORY).first())
        assertEquals(20_000, prefs.dailyBudget(TurnPurpose.PROACTIVE).first())
        assertEquals(10_000, prefs.dailyBudget(TurnPurpose.DIARY).first())
        assertEquals(Int.MAX_VALUE, prefs.dailyBudget(TurnPurpose.CHAT).first())
        assertEquals(Int.MAX_VALUE, prefs.dailyBudget(TurnPurpose.ONBOARDING).first())
        assertEquals(Int.MAX_VALUE, prefs.dailyBudget(TurnPurpose.MOBILE_USE).first())
        assertEquals(8000, prefs.historyTokenBudget.first())
    }

    @Test
    fun settersReadBack() = runTest {
        prefs.setAdultConfirmed(true)
        assertTrue(prefs.adultConfirmed.first())

        prefs.setOnboardingState(OnboardingState.INTERVIEWING)
        assertEquals(OnboardingState.INTERVIEWING, prefs.onboardingState.first())
        prefs.setOnboardingState(OnboardingState.DONE)
        assertEquals(OnboardingState.DONE, prefs.onboardingState.first())

        prefs.setPendingOnboardingProfile("""{"name":"小雨"}""")
        assertEquals("""{"name":"小雨"}""", prefs.pendingOnboardingProfile.first())
        prefs.setPendingOnboardingProfile(null)
        assertNull(prefs.pendingOnboardingProfile.first())

        val quiet = QuietHours(startMinute = 22 * 60 + 30, endMinute = 7 * 60)
        prefs.setQuietHours(quiet)
        assertEquals(quiet, prefs.quietHours.first())

        prefs.setClinginess(Clinginess.HIGH)
        assertEquals(Clinginess.HIGH, prefs.clinginess.first())
        prefs.setClinginess(Clinginess.OFF)
        assertEquals(Clinginess.OFF, prefs.clinginess.first())

        prefs.setDailyBudget(TurnPurpose.MEMORY, 1234)
        prefs.setDailyBudget(TurnPurpose.PROACTIVE, 567)
        prefs.setDailyBudget(TurnPurpose.DIARY, 89)
        assertEquals(1234, prefs.dailyBudget(TurnPurpose.MEMORY).first())
        assertEquals(567, prefs.dailyBudget(TurnPurpose.PROACTIVE).first())
        assertEquals(89, prefs.dailyBudget(TurnPurpose.DIARY).first())

        prefs.setHistoryTokenBudget(4000)
        assertEquals(4000, prefs.historyTokenBudget.first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun quietHoursOutOfRangeRejected() = runTest {
        prefs.setQuietHours(QuietHours(startMinute = 1440, endMinute = 0))
    }
}
