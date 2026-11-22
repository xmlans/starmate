package org.xmyun.starmate.core.data.portable

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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.data.newPrefsDataStore
import org.xmyun.starmate.core.data.prefs.DataStoreCompanionPrefs
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.QuietHours
import org.xmyun.starmate.core.model.TurnPurpose

@RunWith(RobolectricTestRunner::class)
class CompanionPrefsPortableTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newPrefs(name: String) =
        DataStoreCompanionPrefs(newPrefsDataStore(scope, File(tmp.root, "$name.preferences_pb")))

    @Test
    fun exportThenImportIntoFreshPrefs() = runTest {
        val source = newPrefs("source")
        source.setAdultConfirmed(true)
        source.setOnboardingState(OnboardingState.DONE)
        source.setPendingOnboardingProfile("""{"nickname":"阿雨"}""")
        source.setQuietHours(QuietHours(22 * 60, 7 * 60 + 30))
        source.setClinginess(Clinginess.HIGH)
        source.setDailyBudget(TurnPurpose.MEMORY, 111)
        source.setDailyBudget(TurnPurpose.PROACTIVE, 222)
        source.setDailyBudget(TurnPurpose.DIARY, 333)
        source.setHistoryTokenBudget(4444)

        val dir = tmp.newFolder("prefs")
        CompanionPrefsPortable(source).exportTo(dir)
        assertTrue(File(dir, CompanionPrefsPortable.FILE_NAME).isFile)

        val target = newPrefs("target")
        CompanionPrefsPortable(target).importFrom(dir)

        assertTrue(target.adultConfirmed.first())
        assertEquals(OnboardingState.DONE, target.onboardingState.first())
        assertEquals("""{"nickname":"阿雨"}""", target.pendingOnboardingProfile.first())
        assertEquals(QuietHours(22 * 60, 7 * 60 + 30), target.quietHours.first())
        assertEquals(Clinginess.HIGH, target.clinginess.first())
        assertEquals(111, target.dailyBudget(TurnPurpose.MEMORY).first())
        assertEquals(222, target.dailyBudget(TurnPurpose.PROACTIVE).first())
        assertEquals(333, target.dailyBudget(TurnPurpose.DIARY).first())
        assertEquals(4444, target.historyTokenBudget.first())
    }

    @Test
    fun unknownValuesSkippedOthersApplied() = runTest {
        val target = newPrefs("target")
        target.setClinginess(Clinginess.LOW)
        target.setOnboardingState(OnboardingState.INTERVIEWING)
        val dir = tmp.newFolder("prefs")
        File(dir, CompanionPrefsPortable.FILE_NAME).writeText(
            """
            {
              "adultConfirmed": true,
              "onboardingState": "SOMETHING_NEW",
              "clinginess": "EXTREME",
              "quietHours": {"startMinute": 1500, "endMinute": 60},
              "dailyBudgets": {"MEMORY": 5000, "DIARY": "lots"},
              "historyTokenBudget": 6000
            }
            """.trimIndent(),
        )

        CompanionPrefsPortable(target).importFrom(dir)

        assertTrue(target.adultConfirmed.first())
        assertEquals(OnboardingState.INTERVIEWING, target.onboardingState.first())
        assertEquals(Clinginess.LOW, target.clinginess.first())
        assertEquals(QuietHours.DEFAULT, target.quietHours.first())
        assertEquals(5000, target.dailyBudget(TurnPurpose.MEMORY).first())
        assertEquals(10_000, target.dailyBudget(TurnPurpose.DIARY).first())
        assertEquals(20_000, target.dailyBudget(TurnPurpose.PROACTIVE).first())
        assertEquals(6000, target.historyTokenBudget.first())
        assertNull(target.pendingOnboardingProfile.first())
    }

    @Test
    fun invalidJsonThrowsAndKeepsValues() = runTest {
        val target = newPrefs("target")
        target.setOnboardingState(OnboardingState.DONE)
        target.setClinginess(Clinginess.OFF)
        val dir = tmp.newFolder("prefs")
        File(dir, CompanionPrefsPortable.FILE_NAME).writeText("""{"onboardingState": "NOT_STARTED", """)

        try {
            CompanionPrefsPortable(target).importFrom(dir)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
        assertEquals(OnboardingState.DONE, target.onboardingState.first())
        assertEquals(Clinginess.OFF, target.clinginess.first())
        assertFalse(target.adultConfirmed.first())
    }

    @Test
    fun missingFileDoesNothing() = runTest {
        val target = newPrefs("target")
        target.setOnboardingState(OnboardingState.DONE)
        CompanionPrefsPortable(target).importFrom(tmp.newFolder("empty"))
        assertEquals(OnboardingState.DONE, target.onboardingState.first())
    }
}
