package org.xmyun.starmate.feature.proactive.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ResourceProactiveTextsTest {

    private val placeholders = listOf("{{now}}", "{{ambient}}", "{{since_last_chat}}", "{{summary}}", "{{trigger}}")

    private fun texts() = ResourceProactiveTexts(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun english_templateHasAllPlaceholdersAndRules() {
        val texts = texts()
        assertFalse(texts.chinese)
        val template = texts.instructionTemplate()
        placeholders.forEach { assertTrue(it, template.contains(it)) }
        assertTrue(template.contains("\"send\": false"))
        assertTrue(texts.chatGuide().contains("schedule_care"))
        // stable 片段两次读取逐字相同
        assertEquals(texts.chatGuide(), texts.chatGuide())
    }

    @Test
    @Config(qualifiers = "zh")
    fun chinese_templateHasAllPlaceholdersAndRules() {
        val texts = texts()
        assertTrue(texts.chinese)
        val template = texts.instructionTemplate()
        placeholders.forEach { assertTrue(it, template.contains(it)) }
        assertTrue(template.contains("怎么不理我"))
        assertTrue(texts.chatGuide().contains("schedule_care"))
    }
}
