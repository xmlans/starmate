package org.xmyun.starmate.feature.memory

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.ToolRisk
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.memory.consolidate.NewMemory
import org.xmyun.starmate.feature.memory.model.Categories
import org.xmyun.starmate.feature.memory.model.Sources

@RunWith(RobolectricTestRunner::class)
class ToolsTest {
    private val graph = MemoryGraph()
    private val ctx = ToolContext(TurnPurpose.CHAT)

    @After
    fun tearDown() = graph.close()

    private fun fragment(content: String, importance: Int = 2): JsonObject = buildJsonObject {
        put("content", content)
        put("importance", importance)
    }

    @Test
    fun toolsOnlyForChatAndAllRead() = runTest {
        val chat = graph.toolProvider.tools(TurnPurpose.CHAT)
        assertEquals(listOf("remember_fragment", "recall_memory", "read_episode"), chat.map { it.name })
        assertTrue(chat.all { it.risk == ToolRisk.READ })
        for (purpose in listOf(TurnPurpose.ONBOARDING, TurnPurpose.PROACTIVE, TurnPurpose.DIARY, TurnPurpose.MEMORY)) {
            assertTrue(graph.toolProvider.tools(purpose).isEmpty())
        }
        assertEquals("object", chat[0].parametersSchema["type"].toString().trim('"'))
    }

    @Test
    fun fourthFragmentInOneTurnIsRefused() = runTest {
        graph.toolProvider.tools(TurnPurpose.CHAT)
        val results = (1..4).map { graph.rememberTool.execute(fragment("第${it}件小事是关于猫的"), ctx).content }
        assertEquals(List(3) { TestPrompts.zh["tool_remember_ok"] }, results.take(3))
        assertEquals(TestPrompts.zh["tool_remember_limit"], results[3])
        assertEquals(3, graph.repo.allMemories().size)
        // 下一轮重新计数
        graph.toolProvider.tools(TurnPurpose.CHAT)
        assertEquals(TestPrompts.zh["tool_remember_ok"], graph.rememberTool.execute(fragment("第五件"), ctx).content)
    }

    @Test
    fun importanceFiveIsCappedAtFourAndDuplicatesRecognized() = runTest {
        graph.toolProvider.tools(TurnPurpose.CHAT)
        graph.rememberTool.execute(fragment("小明下周三要面试", importance = 5), ctx)
        val m = graph.repo.allMemories().single()
        assertEquals(4, m.importance)
        assertEquals(Categories.FRAGMENT, m.category)
        assertEquals(Sources.AI, m.source)
        val dup = graph.rememberTool.execute(fragment("小明下周三要面试"), ctx)
        assertEquals(TestPrompts.zh["tool_remember_dup"], dup.content)
        assertEquals(1, graph.repo.allMemories().size)
    }

    @Test
    fun readEpisodePagesAtMost1200Characters() = runTest {
        val long = buildString { repeat(3000) { append(('a' + it % 26)) } }
        val id = graph.repo.addEpisode(NOW, long, "短回复", "CHAT")
        val args = buildJsonObject {
            put("episode_id", id)
            put("role", "user")
            put("offset", 0)
            put("length", 5000)
        }
        val first = graph.readTool.execute(args, ctx).content
        val body = first.substringAfter('\n')
        assertEquals(1200, body.length)
        assertTrue(first.contains("next_offset=1200"))
        val second = graph.readTool.execute(
            buildJsonObject {
                put("episode_id", id)
                put("offset", 2400)
                put("length", 1200)
            },
            ctx,
        ).content
        assertEquals(long.substring(2400), second.substringAfter('\n'))
        assertTrue(second.contains("next_offset=${TestPrompts.zh["tool_read_end"]}"))
        val bad = graph.readTool.execute(buildJsonObject { put("episode_id", id); put("role", "x") }, ctx)
        assertTrue(bad.isError)
        assertTrue(graph.readTool.execute(buildJsonObject { put("episode_id", 999) }, ctx).isError)
    }

    @Test
    fun recallReturnsOldFactsAndOriginalWords() = runTest {
        val old = graph.repo.addMemory(NewMemory("小雨以前住在上海", category = Categories.PROFILE), NOW - 100L * 86_400_000)!!
        graph.repo.invalidateMemory(old, NOW - 50L * 86_400_000)
        graph.repo.addEpisode(NOW - 100L * 86_400_000, "我在上海住了五年", "上海很好呀", "CHAT")
        val out = graph.recallTool.execute(buildJsonObject { put("query", "上海 住") }, ctx).content
        assertTrue(out.contains("小雨以前住在上海"))
        assertTrue(out.contains("已过时"))
        assertTrue(out.contains("我在上海住了五年"))
        assertFalse(graph.recallTool.execute(buildJsonObject { put("query", "量子物理") }, ctx).content.contains("<memory_recall>"))
    }
}
