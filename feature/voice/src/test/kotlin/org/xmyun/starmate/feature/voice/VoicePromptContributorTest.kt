package org.xmyun.starmate.feature.voice

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmyun.starmate.core.model.PromptOrder
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.voice.prefs.VoiceFrequency
import org.xmyun.starmate.feature.voice.prefs.VoicePrefs

@RunWith(RobolectricTestRunner::class)
class VoicePromptContributorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var prefs: VoicePrefs
    private val engines = FakeEngineProvider()
    private val clock = FixedClock()

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        prefs = VoicePrefs(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "voice.preferences_pb") }))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun contributor() = VoicePromptContributor(
        prefs = prefs,
        engines = engines,
        texts = RawVoicePromptTexts(ApplicationProvider.getApplicationContext()),
        clock = clock,
    )

    @Test
    fun orderIsVoice() {
        assertEquals(PromptOrder.VOICE, contributor().order)
    }

    @Test
    fun neverReturnsNull() = runTest {
        prefs.setFrequency(VoiceFrequency.NEVER)
        assertNull(contributor().stable(TurnPurpose.CHAT))
    }

    @Test
    fun noEngineReturnsNull() = runTest {
        engines.engine = null
        assertNull(contributor().stable(TurnPurpose.CHAT))
    }

    @Test
    fun threeFrequenciesDiffer() = runTest {
        val texts = listOf(VoiceFrequency.SOMETIMES, VoiceFrequency.OFTEN, VoiceFrequency.ALWAYS).map { frequency ->
            prefs.setFrequency(frequency)
            contributor().stable(TurnPurpose.CHAT)!!
        }
        assertEquals(3, texts.toSet().size)
        texts.forEach { assertTrue(it, it.contains("<voice")) }
    }

    @Test
    fun sameSettingIsByteIdentical() = runTest {
        val first = contributor().stable(TurnPurpose.CHAT)
        val second = contributor().stable(TurnPurpose.CHAT)
        val third = contributor().stable(TurnPurpose.PROACTIVE)
        assertNotNull(first)
        assertEquals(first, second)
        assertEquals(first, third)
    }

    @Test
    fun onlyChatOnboardingAndProactive() = runTest {
        val c = contributor()
        assertNotNull(c.stable(TurnPurpose.CHAT))
        assertNotNull(c.stable(TurnPurpose.ONBOARDING))
        assertNotNull(c.stable(TurnPurpose.PROACTIVE))
        assertNull(c.stable(TurnPurpose.DIARY))
        assertNull(c.stable(TurnPurpose.MEMORY))
        assertNull(c.stable(TurnPurpose.MOBILE_USE))
    }

    @Test
    fun dynamicNoteOnlyWhenQuotaUsedUp() = runTest {
        val c = contributor()
        assertNull(c.dynamic(TurnPurpose.CHAT, "hi"))
        prefs.setDailyLimit(1)
        prefs.consume(1, java.time.LocalDate.now(clock))
        val note = c.dynamic(TurnPurpose.CHAT, "hi")
        assertNotNull(note)
        assertNotEquals(c.stable(TurnPurpose.CHAT), note)
    }

    @Test
    @Config(qualifiers = "zh")
    fun chineseTextsLoadedForChineseLocale() = runTest {
        val text = contributor().stable(TurnPurpose.CHAT)!!
        assertTrue(text, text.contains("语音"))
    }
}
