package org.xmyun.starmate.feature.memory.inject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.feature.memory.NOW
import org.xmyun.starmate.feature.memory.TestPrompts
import org.xmyun.starmate.feature.memory.ZONE
import org.xmyun.starmate.feature.memory.at
import org.xmyun.starmate.feature.memory.model.Categories
import org.xmyun.starmate.feature.memory.model.DAY_MS
import org.xmyun.starmate.feature.memory.model.Episode
import org.xmyun.starmate.feature.memory.model.Gist
import org.xmyun.starmate.feature.memory.model.Mem
import org.xmyun.starmate.feature.memory.retrieval.Lexical
import org.xmyun.starmate.feature.memory.retrieval.TimeExpressionParser
import org.xmyun.starmate.feature.memory.retrieval.hybrid
import org.xmyun.starmate.feature.memory.text.estimateTokens
import org.xmyun.starmate.feature.memory.zhRender

class RenderTest {
    private val render = zhRender()

    @Test
    fun snapshotPutsCoreFirstAndRespectsBudget() {
        val memories = listOf(Mem(1, "名字叫小明", importance = 5)) + (2L..51L).map { Mem(it, "记忆$it" + "啊".repeat(40)) }
        for (budget in listOf(0, 60, 1000)) {
            val (block, _) = render.fitSnapshot(render.selectSnapshot(memories, 0, budget, 30.0), "", budget, 0, 30.0)
            assertTrue(estimateTokens(block) <= budget)
        }
        val (_, chosen) = render.fitSnapshot(render.selectSnapshot(memories, 0, 200, 30.0), "", 200, 0, 30.0)
        assertEquals("名字叫小明", chosen.first().content)
    }

    @Test
    fun stableSnapshotIsByteIdenticalForSameMemories() {
        val memories = listOf(Mem(1, "甲"), Mem(2, "乙", category = Categories.SELF), Mem(3, "丙", eventAt = at(2026, 5, 3)))
        val a = render.renderSnapshot(memories, "概况")
        val b = render.renderSnapshot(memories.reversed(), "概况")
        assertEquals(a, b)
        // 渲染里不带当前时间：换一个“现在”生成，文字仍然一样
        val first = render.fitSnapshot(render.selectSnapshot(memories, NOW, 1000, 30.0), "概况", 1000, NOW, 30.0).first
        val later = render.fitSnapshot(render.selectSnapshot(memories, NOW + 3 * DAY_MS, 1000, 30.0), "概况", 1000, NOW + 3 * DAY_MS, 30.0).first
        assertEquals(first, later)
    }

    @Test
    fun snapshotExcludesArchivedAndInvalid() {
        val memories = listOf(Mem(1, "归档的", archived = true), Mem(2, "失效的", archived = true, invalidAt = 5), Mem(3, "正常的"))
        assertEquals(listOf("正常的"), render.selectSnapshot(memories, 0, 1000, 30.0).map { it.content })
    }

    @Test
    fun selfMemoriesRenderAsBetweenUs() {
        val block = render.renderSnapshot(listOf(Mem(1, "我答应下次给小雨推荐科幻小说", category = Categories.SELF, importance = 4)), "")
        assertTrue(block.contains("【我们之间】\n- 我答应下次给小雨推荐科幻小说"))
    }

    @Test
    fun certaintyMarksOnlyInRecall() {
        val old = Mem(1, "小时候学过游泳")
        assertEquals("- 小时候学过游泳", render.renderLine(old))
        assertTrue(render.renderLine(old, now = 200 * DAY_MS).contains("印象模糊"))
        assertFalse(render.renderLine(old, now = 10 * DAY_MS).contains("印象模糊"))
    }

    @Test
    fun recallRespectsExclusionAndRelevance() {
        val old = Mem(1, "2024 年去过冰岛看极光", tags = "旅行", archived = true)
        val shown = Mem(2, "喜欢极光摄影")
        val memories = listOf(old, shown)
        val lexical = Lexical.ofMemories(memories)
        val lex = lexical.scores(lexical.query("想再去冰岛看一次极光"))
        val rel = hybrid(lex, null, memories.map { it.id })
        val pick = render.selectRecall(memories, rel, lex, null, emptyList(), emptyMap(), emptyMap(), null, null, setOf(2L), 1000, 3)
        assertEquals(listOf(1L), pick.memories.map { it.id })
        val lex2 = lexical.scores(lexical.query("今天吃什么"))
        val none = render.selectRecall(memories, hybrid(lex2, null, listOf(1L)), lex2, null, emptyList(), emptyMap(), emptyMap(), null, null, emptySet(), 1000, 3)
        assertTrue(none.memories.isEmpty())
    }

    @Test
    fun linksSpreadRecall() {
        val sick = Mem(1, "年糕得了肠胃炎")
        val worry = Mem(2, "小雨那周很着急，请假带它看医生")
        val other = Mem(3, "小雨喜欢陈绮贞")
        val memories = listOf(sick, worry, other)
        val lexical = Lexical.ofMemories(memories)
        val lex = lexical.scores(lexical.query("年糕最近怎么样"))
        val rel = hybrid(lex, null, memories.map { it.id })
        val links = mapOf(1L to listOf(2L to 0.8), 2L to listOf(1L to 0.8))
        val with = render.selectRecall(memories, rel, lex, null, emptyList(), emptyMap(), emptyMap(), null, null, emptySet(), 1000, 0, links = links)
        assertEquals(listOf(1L, 2L), with.memories.map { it.id })
        val without = render.selectRecall(memories, rel, lex, null, emptyList(), emptyMap(), emptyMap(), null, null, emptySet(), 1000, 0)
        assertEquals(listOf(1L), without.memories.map { it.id })
    }

    @Test
    fun episodeRecallByTime() {
        val day = at(2026, 10, 20, 20)
        val episodes = listOf(Episode(1, day - 30 * DAY_MS, "周末去爬山了", "不错"), Episode(2, day, "今天面试好紧张", "加油"))
        val span = TimeExpressionParser.parse("上周我跟你说的那件事", NOW, ZONE)
        val lexical = Lexical.ofEpisodes(episodes)
        val lex = lexical.scores(lexical.query("上周我跟你说的那件事"))
        val rel = hybrid(lex, null, episodes.map { it.id })
        val pick = render.selectRecall(emptyList(), emptyMap(), emptyMap(), null, episodes, rel, lex, null, span, emptySet(), 1000, 3)
        assertEquals(listOf("今天面试好紧张"), pick.episodes.map { it.userText })
    }

    @Test
    fun gistRecallByTime() {
        val gist = Gist(1, NOW - 40 * DAY_MS, NOW - 40 * DAY_MS, 1, 1, "小雨聊到实习面试通过，很开心")
        val span = org.xmyun.starmate.feature.memory.model.Span(NOW - 45 * DAY_MS, NOW - 35 * DAY_MS)
        val pick = render.selectRecall(emptyList(), emptyMap(), emptyMap(), null, emptyList(), emptyMap(), emptyMap(), null, span, emptySet(), 1000, 3, gists = listOf(gist))
        assertEquals(listOf("小雨聊到实习面试通过，很开心"), pick.gists.map { it.summary })
        assertTrue(render.renderRecall(pick, NOW).contains("那段时间"))
    }

    @Test
    fun recallStaysWithinBudget() {
        val memories = (1L..40L).map { Mem(it, "猫咪团子的第${it}件趣事" + "很长".repeat(20), archived = true) }
        val lexical = Lexical.ofMemories(memories)
        val lex = lexical.scores(lexical.query("猫咪团子趣事"))
        val rel = hybrid(lex, null, memories.map { it.id })
        val pick = render.selectRecall(memories, rel, lex, null, emptyList(), emptyMap(), emptyMap(), null, null, emptySet(), 300, 3)
        assertTrue(pick.memories.isNotEmpty())
        assertTrue(estimateTokens(render.renderRecall(pick, NOW)) <= 300)
    }

    @Test
    fun dueRemindersOncePerThreeDays() {
        val soon = Mem(1, "小雨面试产品经理", category = Categories.EVENT, eventAt = NOW + (0.8 * DAY_MS).toLong())
        val past = Mem(2, "小雨考了驾照科目二", category = Categories.EVENT, eventAt = NOW - (1.5 * DAY_MS).toLong())
        val far = Mem(3, "小雨去年去过冰岛", category = Categories.EVENT, eventAt = NOW - 300 * DAY_MS)
        val due = dueReminders(listOf(soon, past, far), NOW)
        assertEquals(setOf(1L, 2L), due.map { it.id }.toSet())
        assertTrue(render.renderCare(due, NOW).contains("明天"))
        // 提醒过的 3 天内不再提，3 天后可以再提
        val nudged = listOf(soon.copy(nudgedAt = NOW), past)
        assertEquals(listOf(2L), dueReminders(nudged, NOW + 3600_000).map { it.id })
        assertTrue(dueReminders(nudged, NOW + (2.9 * DAY_MS).toLong()).isEmpty())
        assertEquals(listOf(1L), dueReminders(nudged, NOW + (3.1 * DAY_MS).toLong()).map { it.id })
    }

    @Test
    fun englishPromptHasSameKeys() {
        val zhKeys = listOf(
            "snapshot_header", "recall_header", "care_header", "consolidate_system", "compact_system", "reflect_system",
            "tool_remember_desc", "tool_read_header", "batch_line", "episode_line", "day_-2", "day_2",
        )
        for (key in zhKeys) {
            TestPrompts.zh[key]
            TestPrompts.en[key]
        }
        assertTrue(TestPrompts.en["compact_system"].contains("{need}"))
    }
}
