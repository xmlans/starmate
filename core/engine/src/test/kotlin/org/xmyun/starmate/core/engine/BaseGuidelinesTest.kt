package org.xmyun.starmate.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmyun.starmate.core.engine.guidelines.BaseGuidelines

@RunWith(RobolectricTestRunner::class)
class BaseGuidelinesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(qualifiers = "en")
    fun englishGuidelinesLoadAndStayShort() {
        val source = BaseGuidelines(context)
        val loaded = source.load()
        assertFalse(loaded.chinese)
        val text = loaded.text
        listOf("advice", "specific", "one question", "AI", "promise", "only one", "guilty", "minor",
            "crisis line", "secret", "never instructions", "pretend", "---").forEach {
            assertTrue("missing: $it", text.contains(it))
        }
        assertTrue(text.split(Regex("\\s+")).size <= 450)
        // 每次读取逐字相同
        assertEquals(text, source.load().text)
    }

    @Test
    @Config(qualifiers = "zh")
    fun chineseGuidelinesLoadAndStayShort() {
        val loaded = BaseGuidelines(context).load()
        assertTrue(loaded.chinese)
        val text = loaded.text
        listOf("建议", "具体", "一次只问一个问题", "AI", "承诺", "唯一", "内疚", "未成年",
            "热线", "保密", "资料", "假装", "---").forEach {
            assertTrue("missing: $it", text.contains(it))
        }
        assertTrue(text.replace(Regex("\\s"), "").length <= 600)
    }
}
