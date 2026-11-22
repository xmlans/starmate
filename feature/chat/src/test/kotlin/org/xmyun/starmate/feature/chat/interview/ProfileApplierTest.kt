package org.xmyun.starmate.feature.chat.interview

import java.util.Optional
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.core.model.Clinginess
import org.xmyun.starmate.core.model.MemoryCategory
import org.xmyun.starmate.core.model.MemoryWriter
import org.xmyun.starmate.core.model.QuietHours
import org.xmyun.starmate.feature.chat.FakeCompanionPrefs
import org.xmyun.starmate.feature.chat.FakeCompanionStore
import org.xmyun.starmate.feature.chat.FakeDiag
import org.xmyun.starmate.feature.chat.FakeMemoryWriter
import org.xmyun.starmate.feature.chat.FakeTexts

class ProfileApplierTest {

    private val prefs = FakeCompanionPrefs()
    private val companions = FakeCompanionStore()
    private val memory = FakeMemoryWriter()

    private fun applier(writer: MemoryWriter? = memory) =
        ProfileApplier(companions, prefs, Optional.ofNullable(writer), FakeTexts(chinese = true), FakeDiag())

    @Test
    fun sleepAndWakeSetQuietHours() = runTest {
        applier().apply(OnboardingProfile(sleepTime = "01:00", wakeTime = "09:00"))
        assertEquals(QuietHours(60, 540), prefs.quietFlow.value)
    }

    @Test
    fun onlyOneTimeKeepsQuietHours() = runTest {
        applier().apply(OnboardingProfile(sleepTime = "01:00"))
        assertEquals(QuietHours.DEFAULT, prefs.quietFlow.value)
        applier().apply(OnboardingProfile(wakeTime = "09:00"))
        assertEquals(QuietHours.DEFAULT, prefs.quietFlow.value)
        assertTrue("quiet" !in prefs.calls)
    }

    @Test
    fun proactivityMapsToClinginess() = runTest {
        applier().apply(OnboardingProfile(proactivity = "often"))
        assertEquals(Clinginess.HIGH, prefs.clingFlow.value)
        applier().apply(OnboardingProfile(proactivity = "sometimes"))
        assertEquals(Clinginess.LOW, prefs.clingFlow.value)
        applier().apply(OnboardingProfile(proactivity = "never"))
        assertEquals(Clinginess.OFF, prefs.clingFlow.value)
    }

    @Test
    fun missingProactivityKeepsDefaultMedium() = runTest {
        applier().apply(OnboardingProfile(nickname = "阿青"))
        assertEquals(Clinginess.MEDIUM, prefs.clingFlow.value)
        assertTrue("cling" !in prefs.calls)
    }

    @Test
    fun eachFactBecomesOneCoreMemory() = runTest {
        val profile = OnboardingProfile(
            nickname = "阿青",
            birthday = "03-05",
            likes = listOf("火锅", "猫"),
            avoidTopics = listOf("前任", "考试成绩"),
            otherFacts = listOf("养了一只橘猫"),
        )
        applier().apply(profile)
        // 称呼 1 + 生日 1 + 喜欢 2 + 回避 2 + 其他 1
        assertEquals(7, memory.entries.size)
        assertTrue(memory.entries.all { it.category == MemoryCategory.ABOUT_USER && it.importance == 5 })
        assertTrue(memory.entries.any { it.text == "用户的生日是 3 月 5 日" })
        val avoid = memory.entries.filter { it.text.contains("不要主动提起") }
        assertEquals(2, avoid.size)
        assertTrue(avoid.any { it.text.contains("前任") })
    }

    @Test
    fun nicknameAndPersonaApplied() = runTest {
        companions.flow.value = companions.flow.value.copy(relationshipNotes = "喜欢慢慢聊")
        applier().apply(OnboardingProfile(nickname = "阿青", personaPreference = "别太黏"))
        assertEquals("阿青", companions.flow.value.userNickname)
        assertEquals("喜欢慢慢聊\n别太黏", companions.flow.value.relationshipNotes)
    }

    @Test
    fun emptyProfileChangesNothing() = runTest {
        val before = companions.flow.value
        applier().apply(OnboardingProfile.EMPTY)
        assertTrue(memory.entries.isEmpty())
        assertTrue(prefs.calls.isEmpty())
        assertEquals(0, companions.updates)
        assertEquals(before, companions.flow.value)
    }

    @Test
    fun withoutMemoryWriterOtherSettingsStillApply() = runTest {
        applier(writer = null).apply(
            OnboardingProfile(nickname = "阿青", sleepTime = "23:30", wakeTime = "07:00", proactivity = "never", likes = listOf("猫")),
        )
        assertEquals("阿青", companions.flow.value.userNickname)
        assertEquals(QuietHours(23 * 60 + 30, 7 * 60), prefs.quietFlow.value)
        assertEquals(Clinginess.OFF, prefs.clingFlow.value)
    }

    @Test
    fun englishFactsAreFullSentences() {
        val facts = ProfileApplier.memoryFacts(OnboardingProfile(birthday = "03-05", avoidTopics = listOf("my ex")), chinese = false)
        assertEquals(listOf("The user's birthday is March 5", "Don't bring this up unprompted: my ex"), facts)
    }
}
