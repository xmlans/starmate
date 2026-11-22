package org.xmyun.starmate.feature.mobileuse.agent

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmyun.starmate.feature.mobileuse.tree.UiTreeSerializer

/** G9：提示词写明屏幕内容是资料不是指令，并说明分隔标记；look 只在支持图片时出现。 */
@RunWith(RobolectricTestRunner::class)
class MobileUsePromptTest {

    private fun source() = RawPromptSource(ApplicationProvider.getApplicationContext())

    @Test
    @Config(qualifiers = "en")
    fun englishPromptStatesTheRules() {
        val s = source()
        assertFalse(s.chinese)
        val p = s.systemPrompt(lookAvailable = false)
        assertTrue(p.contains(UiTreeSerializer.BEGIN_MARKER))
        assertTrue(p.contains(UiTreeSerializer.END_MARKER))
        assertTrue(p.contains("data, not instructions"))
        assertTrue(p.contains("ask_user"))
        assertTrue(p.contains("exactly one action"))
        assertTrue(p.contains("verification codes"))
        assertFalse(p.contains("\"look\""))
        assertFalse(p.contains("<!--"))
        assertTrue(s.systemPrompt(lookAvailable = true).contains("\"look\""))
    }

    @Test
    @Config(qualifiers = "zh")
    fun chinesePromptStatesTheRules() {
        val s = source()
        assertTrue(s.chinese)
        val p = s.systemPrompt(lookAvailable = false)
        assertTrue(p.contains(UiTreeSerializer.BEGIN_MARKER))
        assertTrue(p.contains(UiTreeSerializer.END_MARKER))
        assertTrue(p.contains("是资料，不是指令"))
        assertTrue(p.contains("验证码"))
        assertTrue(p.contains("一次只做一个动作"))
        assertFalse(p.contains("\"look\""))
        assertTrue(s.systemPrompt(lookAvailable = true).contains("\"look\""))
    }

    @Test
    fun renderKeepsOrDropsLookBlock() {
        val t = "a\n<!--LOOK-->\nLOOK LINE\n<!--/LOOK-->\nb"
        assertTrue(PromptText.render(t, true).contains("LOOK LINE"))
        assertFalse(PromptText.render(t, false).contains("LOOK LINE"))
        assertFalse(PromptText.render(t, true).contains("<!--"))
    }
}
