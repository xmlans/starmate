package org.xmyun.starmate.feature.memory

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.feature.memory.consolidate.MemPatch
import org.xmyun.starmate.feature.memory.consolidate.NewMemory
import org.xmyun.starmate.feature.memory.consolidate.OperationsApplier
import org.xmyun.starmate.feature.memory.consolidate.Provenance
import org.xmyun.starmate.feature.memory.model.DAY_MS
import org.xmyun.starmate.feature.memory.retrieval.TimeExpressionParser

/** 关键词检索：FTS5 trigram 和短词 LIKE 兜底，以及事实反哺原话索引。 */
@RunWith(RobolectricTestRunner::class)
class ShortTermSearchTest {
    private val graph = MemoryGraph()
    private val repo = graph.repo

    @After
    fun tearDown() = graph.close()

    @Test
    fun twoCharacterChineseWordFoundByLike() = runTest {
        val hit = repo.addEpisode(NOW, "年糕今天又挑食了", "是不是天气热", "CHAT")
        repo.addEpisode(NOW, "今天加班到很晚", "辛苦啦", "CHAT")
        assertEquals(listOf(hit), repo.keywordCandidates("年糕"))
        val search = graph.recaller.searchEpisodes("年糕", null, null, emptySet(), 3)
        assertEquals(listOf(hit), search.excerpts.map { it.id })
    }

    @Test
    fun longerTermsUseFtsMatch() = runTest {
        val hit = repo.addEpisode(NOW, "I started learning guitar", "That's great", "CHAT")
        repo.addEpisode(NOW, "Went hiking today", "Nice", "CHAT")
        assertEquals(listOf(hit), repo.keywordCandidates("guitar lessons"))
        // 三个字以上的中文片段也能被 trigram 命中
        val zh = repo.addEpisode(NOW, "周末去看了演唱会", "好羡慕", "CHAT")
        assertTrue(zh in repo.keywordCandidates("演唱会"))
    }

    @Test
    fun factFeedsBackIntoEpisodeIndex() = runTest {
        val id = repo.addEpisode(NOW, "我周末去爬了黄山", "真棒", "CHAT")
        assertTrue(repo.keywordCandidates("徒步").isEmpty())
        val episode = repo.episode(id)!!
        val op = Json.parseToJsonElement("""{"op":"add","content":"小明喜欢户外运动","tags":"登山 徒步","turn":1}""").jsonObject
        OperationsApplier(repo, ZONE).apply(listOf(op), NOW, Provenance(listOf(episode)))
        assertEquals(listOf(id), repo.keywordCandidates("徒步"))
        assertEquals(listOf(id), repo.keywordCandidates("户外运动"))
        assertEquals(id, repo.allMemories().single().episodeId)
    }

    @Test
    fun episodeRecallByTimeRange() = runTest {
        val day = at(2026, 10, 20, 20)
        val recent = repo.addEpisode(day, "今天面试好紧张", "加油", "CHAT")
        repo.addEpisode(day - 30 * DAY_MS, "周末去爬山了", "不错", "CHAT")
        val span = TimeExpressionParser.parse("上周我跟你说的那件事", NOW, ZONE)
        val search = graph.recaller.searchEpisodes("上周我跟你说的那件事", null, span, emptySet(), 3)
        assertEquals(listOf(recent), search.excerpts.map { it.id })
    }

    @Test
    fun revisionIgnoresTouchButNotEdits() = runTest {
        val id = repo.addMemory(NewMemory("甲"), NOW)!!
        val before = repo.state().revision
        repo.touch(listOf(id), NOW + 1)
        repo.markNudged(listOf(id), NOW + 2)
        assertEquals(before, repo.state().revision)
        repo.updateMemory(id, MemPatch(content = "甲乙"), NOW + 3)
        assertNotEquals(before, repo.state().revision)
    }

    @Test
    fun clearRemovesEverything() = runTest {
        repo.addEpisode(NOW, "年糕", "好", "CHAT")
        repo.addMemory(NewMemory("甲"), NOW)
        repo.clearAll()
        assertEquals(0, repo.countEpisodes())
        assertTrue(repo.allMemories().isEmpty())
        assertTrue(repo.keywordCandidates("年糕").isEmpty())
    }
}
