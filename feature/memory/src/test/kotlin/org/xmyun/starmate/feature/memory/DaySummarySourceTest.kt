package org.xmyun.starmate.feature.memory

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.model.MemoryCategory
import org.xmyun.starmate.feature.memory.model.Categories

@RunWith(RobolectricTestRunner::class)
class DaySummarySourceTest {
    private val graph = MemoryGraph()
    private val from = Instant.ofEpochMilli(at(2026, 10, 29, 4))
    private val to = Instant.ofEpochMilli(at(2026, 10, 29, 23, 30))

    @After
    fun tearDown() = graph.close()

    private suspend fun dayOfChat() {
        graph.repo.addEpisode(at(2026, 10, 28, 20), "昨天的事", "嗯", "CHAT")
        graph.repo.addEpisode(at(2026, 10, 29, 9), "早上好，今天要去面试", "加油！", "CHAT")
        graph.repo.addEpisode(at(2026, 10, 29, 20), "面试过了！", "太棒了", "CHAT")
        graph.repo.addEpisode(at(2026, 10, 29, 21), "", "今晚早点休息哦", "PROACTIVE")
    }

    @Test
    fun refreshFalseNeverCallsTheModel() = runTest {
        dayOfChat()
        val summary = graph.daySummary.daySummary(from, to, refresh = false)!!
        assertTrue(graph.engine.calls.isEmpty())
        assertEquals(2, summary.turnCount)
        assertEquals("", summary.gist)
    }

    @Test
    fun refreshTrueConsolidatesFirst() = runTest {
        dayOfChat()
        graph.engine.responder = {
            FakeEngine.ok("""{"ops":[{"op":"add","content":"小明今天面试通过了","category":"event","importance":3,"turn":2}],"gist":"小明聊到面试通过，很开心"}""")
        }
        val summary = graph.daySummary.daySummary(from, to, refresh = true)!!
        assertTrue(graph.engine.calls.isNotEmpty())
        assertTrue(summary.gist.contains("面试通过"))
        assertEquals(listOf("小明今天面试通过了"), summary.newMemories)
        assertEquals(2, summary.turnCount)
    }

    @Test
    fun noConversationReturnsNull() = runTest {
        assertNull(graph.daySummary.daySummary(from, to, refresh = true))
        graph.repo.addEpisode(at(2026, 10, 27, 20), "前天的事", "嗯", "CHAT")
        assertNull(graph.daySummary.daySummary(from, to, refresh = true))
        assertTrue(graph.engine.calls.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
class MemoryWriterTest {
    private val graph = MemoryGraph()

    @After
    fun tearDown() = graph.close()

    @Test
    fun categoriesLandInTheRightLayer() = runTest {
        graph.writer.add("用户的生日是 3 月 5 日", MemoryCategory.ABOUT_USER, 5)
        graph.writer.add("我们约好每周五一起看电影", MemoryCategory.BETWEEN_US, 3)
        graph.writer.add("一条普通的记忆", "something_else", 3)
        val all = graph.repo.allMemories().associateBy { it.content }
        val about = all.getValue("用户的生日是 3 月 5 日")
        assertTrue(about.pinned && about.isCore)
        assertEquals(Categories.PROFILE, about.category)
        val between = all.getValue("我们约好每周五一起看电影")
        assertEquals(Categories.SELF, between.category)
        assertEquals(3, between.importance)
        assertTrue(!between.pinned && !between.archived)
        assertEquals(Categories.OTHER, all.getValue("一条普通的记忆").category)
        // 记忆页分组：核心、我们之间、活跃
        val groups = org.xmyun.starmate.feature.memory.ui.MemoryGroups.of(graph.repo.allMemories(), "", "")
        assertEquals(listOf("用户的生日是 3 月 5 日"), groups.core.map { it.content })
        assertEquals(listOf("我们约好每周五一起看电影"), groups.betweenUs.map { it.content })
        assertEquals(listOf("一条普通的记忆"), groups.active.map { it.content })
    }

    @Test
    fun duplicateWriteDoesNotCreateSecondMemory() = runTest {
        graph.writer.add("不要主动提起：前任", MemoryCategory.ABOUT_USER, 5)
        graph.writer.add("不要主动提起：前任", MemoryCategory.ABOUT_USER, 5)
        assertEquals(1, graph.repo.allMemories().size)
        graph.writer.add("   ", MemoryCategory.ABOUT_USER, 5)
        assertEquals(1, graph.repo.allMemories().size)
    }
}
