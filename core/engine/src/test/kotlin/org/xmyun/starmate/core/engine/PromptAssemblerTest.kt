package org.xmyun.starmate.core.engine

import java.time.Duration
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UserInput

class PromptAssemblerTest {

    private fun harness(client: FakeLlmClient = FakeLlmClient()) = EngineHarness(
        client = client,
        contributors = setOf(
            FakeContributor(order = 300, stableText = "片段C"),
            FakeContributor(order = 100, stableText = "片段A", dynamicText = "回想：上周去了海边"),
            FakeContributor(order = 900, stableText = "   "),
            FakeContributor(order = 200, stableText = "片段B"),
            FakeContributor(order = 150, stableText = null),
        ),
        ambientSources = setOf(FakeAmbientSource("steps") { "今天步数 3200" }),
    )

    @Test
    fun stablePartIsByteIdenticalWhenOnlyTimeChanges() = runTest {
        val h = harness()
        val first = h.assembler.stable(TurnPurpose.CHAT)
        val dynamicFirst = h.assembler.dynamic(TurnPurpose.CHAT, "在吗")
        h.clock.advance(Duration.ofHours(3).plusMinutes(17))
        val second = h.assembler.stable(TurnPurpose.CHAT)
        val dynamicSecond = h.assembler.dynamic(TurnPurpose.CHAT, "在吗")

        assertEquals(first, second)
        assertNotEquals(dynamicFirst, dynamicSecond)
        // 时间和环境信息只在动态部分
        assertFalse(first.contains("现在是"))
        assertFalse(first.contains("步数"))
        assertTrue(dynamicFirst.startsWith("现在是 2026年11月11日 星期三 21:05"))
        assertTrue(dynamicFirst.contains("今天步数 3200"))
    }

    @Test
    fun stablePartAcrossTwoEngineTurnsIsIdentical() = runTest {
        val client = FakeLlmClient()
        val h = harness(client)
        h.engine.send(UserInput.Text("第一句")).toList()
        h.clock.advance(Duration.ofMinutes(42))
        h.engine.send(UserInput.Text("第二句")).toList()

        assertEquals(2, client.requests.size)
        assertEquals(client.requests[0].system.stable, client.requests[1].system.stable)
        assertEquals("", client.requests[0].system.volatile)
        assertNotEquals(client.requests[0].messages.last().text, client.requests[1].messages.last().text)
    }

    @Test
    fun stableOrderIsGuidelinesPersonaThenContributorsByOrder() = runTest {
        val stable = harness().assembler.stable(TurnPurpose.CHAT)
        val guide = stable.indexOf("【内置准则】")
        val persona = stable.indexOf("你叫小星。")
        val a = stable.indexOf("片段A")
        val b = stable.indexOf("片段B")
        val c = stable.indexOf("片段C")
        assertEquals(0, guide)
        assertTrue(guide < persona && persona < a && a < b && b < c)
        assertTrue(stable.contains("温柔、爱笑的女孩。"))
        assertTrue(stable.contains("喜欢被叫醒时轻一点。"))
        assertTrue(stable.contains("你称呼对方“阿远”。"))
        // 非空片段之间用空行隔开，空白片段不留痕迹
        assertTrue(stable.contains("片段A\n\n片段B\n\n片段C"))
        assertTrue(stable.endsWith("片段C"))
    }

    @Test
    fun dynamicGoesToModelButNotToStoredUserMessage() = runTest {
        val client = FakeLlmClient()
        val h = harness(client)
        h.engine.send(UserInput.Text("今天好累")).toList()

        val sent = client.requests.single().messages.last()
        assertEquals(Role.USER, sent.role)
        assertTrue(sent.text.startsWith("今天好累\n\n---\n"))
        assertTrue(sent.text.contains("现在是 "))
        assertTrue(sent.text.contains("回想：上周去了海边"))

        val stored = h.store.all.first { it.role == Role.USER }
        assertEquals("今天好累", stored.rawText)
        assertEquals("今天好累", stored.displayText)
    }

    @Test
    fun kickoffSendsOnlyDynamicPart() = runTest {
        val h = harness()
        val dynamic = h.assembler.dynamic(TurnPurpose.ONBOARDING, null)
        assertEquals(dynamic, h.assembler.userContent(null, dynamic))
        assertEquals("hi", h.assembler.userContent("hi", ""))
    }

    @Test
    fun englishGuidelinesUseEnglishPersonaAndTime() = runTest {
        val h = EngineHarness(guidelines = FakeGuidelines("GUIDE", chinese = false))
        val stable = h.assembler.stable(TurnPurpose.CHAT)
        assertTrue(stable.contains("Your name is 小星."))
        val dynamic = h.assembler.dynamic(TurnPurpose.CHAT, null)
        assertTrue(dynamic.startsWith("Now: Wednesday, November 11, 2026, 21:05"))
    }
}
