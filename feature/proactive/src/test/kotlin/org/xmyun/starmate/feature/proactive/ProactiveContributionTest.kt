package org.xmyun.starmate.feature.proactive

import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xmyun.starmate.core.model.PromptOrder
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.proactive.model.Care
import org.xmyun.starmate.feature.proactive.tools.CancelCareTool
import org.xmyun.starmate.feature.proactive.tools.CareService
import org.xmyun.starmate.feature.proactive.tools.ListCaresTool
import org.xmyun.starmate.feature.proactive.tools.ProactiveToolProvider
import org.xmyun.starmate.feature.proactive.tools.ScheduleCareTool
import org.xmyun.starmate.feature.proactive.work.InstructionBuilder
import org.xmyun.starmate.feature.proactive.work.InstructionContext

class ProactiveContributionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = MutableClock(local("2026-11-12T10:00"))

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun prefs() = newPrefs()

    @Test
    fun toolsOnlyForChat_andOnlyWhenEnabled() = runTest {
        val prefs = prefs()
        val service = CareService(InMemoryCareStore(), FakeScheduler(), FixedRandom(), clock)
        val provider = ProactiveToolProvider(
            prefs,
            ScheduleCareTool(service, FakeNotifier(), clock),
            CancelCareTool(service),
            ListCaresTool(service, clock),
        )
        assertEquals(listOf("schedule_care", "cancel_care", "list_cares"), provider.tools(TurnPurpose.CHAT).map { it.name })
        for (purpose in TurnPurpose.entries - TurnPurpose.CHAT) {
            assertTrue("$purpose", provider.tools(purpose).isEmpty())
        }
        assertTrue(provider.tools(TurnPurpose.CHAT).all { it.risk == org.xmyun.starmate.core.model.ToolRisk.READ })
        prefs.setEnabled(false)
        assertTrue(provider.tools(TurnPurpose.CHAT).isEmpty())
    }

    @Test
    fun promptContributor_onlyForChatWhenEnabled() = runTest {
        val prefs = prefs()
        val contributor = ProactivePromptContributor(prefs, FakeTexts())
        assertEquals(PromptOrder.PROACTIVE, contributor.order)
        assertEquals("GUIDE", contributor.stable(TurnPurpose.CHAT))
        assertNull(contributor.stable(TurnPurpose.PROACTIVE))
        assertNull(contributor.dynamic(TurnPurpose.CHAT, "hi"))
        prefs.setEnabled(false)
        assertNull(contributor.stable(TurnPurpose.CHAT))
    }

    @Test
    fun instruction_chinese_fillsAllPlaceholders() {
        val template = "{{now}}|{{ambient}}|{{since_last_chat}}|{{summary}}|{{trigger}}"
        val care = Care(
            topic = "面试",
            reason = "问问结果",
            scheduledFor = local("2026-11-12T18:00"),
            nextAttemptAt = local("2026-11-12T18:00"),
            createdAt = clock.now,
            updatedAt = clock.now,
        )
        val text = InstructionBuilder.build(
            template,
            chinese = true,
            ctx = InstructionContext(
                now = local("2026-11-12T18:05").atZone(TEST_ZONE),
                ambient = emptyList(),
                sinceLastChat = Duration.ofMinutes(26 * 60 + 5),
                summary = null,
                care = care,
            ),
        )
        assertFalse(text, text.contains("{{"))
        val parts = text.split("|")
        assertEquals("2026年11月12日 星期四 18:05", parts[0])
        assertEquals("无", parts[1])
        assertEquals("1 天 2 小时", parts[2])
        assertEquals("无", parts[3])
        assertTrue(parts[4].contains("“面试”"))
        assertTrue(parts[4].contains("问问结果"))
    }
}
