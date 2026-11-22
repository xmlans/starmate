package org.xmyun.starmate.feature.chat.interview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingProfileTest {

    @Test
    fun fullJson() {
        val json = """
            {
              "nickname": "阿青",
              "birthday": "03-05",
              "sleepTime": "01:00",
              "wakeTime": "09:00",
              "occupation": "读研",
              "likes": ["火锅", "猫"],
              "dislikes": ["香菜"],
              "personaPreference": "温柔一点，别太黏",
              "proactivity": "sometimes",
              "avoidTopics": ["前任"],
              "otherFacts": ["养了一只橘猫"]
            }
        """.trimIndent()
        val profile = OnboardingProfile.parse(json)
        assertEquals("阿青", profile.nickname)
        assertEquals("03-05", profile.birthday)
        assertEquals("01:00", profile.sleepTime)
        assertEquals("09:00", profile.wakeTime)
        assertEquals("读研", profile.occupation)
        assertEquals(listOf("火锅", "猫"), profile.likes)
        assertEquals(listOf("香菜"), profile.dislikes)
        assertEquals("温柔一点，别太黏", profile.personaPreference)
        assertEquals("sometimes", profile.proactivity)
        assertEquals(listOf("前任"), profile.avoidTopics)
        assertEquals(listOf("养了一只橘猫"), profile.otherFacts)
        // 往返一致
        assertEquals(profile, OnboardingProfile.parse(profile.toJson()))
    }

    @Test
    fun partialFields() {
        val profile = OnboardingProfile.parse("""{"nickname":"小林","sleepTime":"1:30"}""")
        assertEquals("小林", profile.nickname)
        assertEquals("01:30", profile.sleepTime)
        assertNull(profile.wakeTime)
        assertNull(profile.likes)
        assertNull(profile.proactivity)
    }

    @Test
    fun extraFieldsIgnored() {
        val profile = OnboardingProfile.parse("""{"nickname":"小林","mood":"happy","age":20,"likes":"跑步"}""")
        assertEquals("小林", profile.nickname)
        // 单个字符串当作只有一项的列表
        assertEquals(listOf("跑步"), profile.likes)
    }

    @Test
    fun codeFenceAndWrapperAccepted() {
        val text = "好的：\n```json\n{\"profile\": {\"nickname\": \"Ann\", \"proactivity\": \"OFTEN\"}}\n```"
        val profile = OnboardingProfile.parse(text)
        assertEquals("Ann", profile.nickname)
        assertEquals("often", profile.proactivity)
    }

    @Test
    fun invalidJsonGivesEmptyProfile() {
        assertTrue(OnboardingProfile.parse("not json at all").isEmpty())
        assertTrue(OnboardingProfile.parse("{nickname: }").isEmpty())
        assertTrue(OnboardingProfile.parse("").isEmpty())
        assertTrue(OnboardingProfile.parse(null).isEmpty())
        assertTrue(OnboardingProfile.parse("[1,2,3]").isEmpty())
    }

    @Test
    fun invalidValuesDropped() {
        val profile = OnboardingProfile.parse("""{"sleepTime":"半夜","wakeTime":"25:00","proactivity":"always","nickname":"  "}""")
        assertTrue(profile.isEmpty())
    }
}
