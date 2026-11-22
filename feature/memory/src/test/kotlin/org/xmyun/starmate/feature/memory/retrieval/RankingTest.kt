package org.xmyun.starmate.feature.memory.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.feature.memory.model.Mem
import org.xmyun.starmate.feature.memory.text.estimateTokens
import org.xmyun.starmate.feature.memory.text.similarity
import org.xmyun.starmate.feature.memory.text.tokens

class RankingTest {

    @Test
    fun tokensAndEstimate() {
        assertEquals(setOf("你好", "好世", "世界"), tokens("你好世界"))
        assertTrue("guitar" in tokens("I play Guitar"))
        assertEquals(4, estimateTokens("你好世界"))
        assertEquals(2, estimateTokens("abcdefgh"))
        assertTrue(similarity("小雨在字节实习做后端", "小雨在字节实习时住在徐汇") > 0.3)
    }

    @Test
    fun lexicalUsesTagsAndContext() {
        val cat = Mem(1, "养了一只叫团子的橘猫", tags = "宠物 猫咪 动物")
        val guitar = Mem(2, "在学吉他")
        val lexical = Lexical.ofMemories(listOf(cat, guitar))
        val scores = lexical.scores(lexical.query("它最近怎么样", "我家宠物最近不爱吃饭"))
        assertEquals(listOf(1L), scores.keys.toList())
    }

    @Test
    fun singleTermQueryCountsAsEnoughHits() {
        val lexical = Lexical.ofMemories(listOf(Mem(1, "年糕得了肠胃炎"), Mem(2, "喜欢陈绮贞")))
        val scores = lexical.scores(lexical.query("年糕"))
        assertEquals(2, scores.getValue(1).hits)
    }

    @Test
    fun hybridAndCosine() {
        val cos = Vectors.cosineMany(floatArrayOf(1f, 0f), mapOf(1L to floatArrayOf(1f, 0f), 2L to floatArrayOf(0f, 1f), 3L to floatArrayOf(1f, 1f, 1f)))
        assertEquals(1.0, cos.getValue(1), 1e-6)
        assertEquals(0.0, cos.getValue(2), 1e-6)
        assertFalse(3L in cos) // 维度不同的向量被忽略
        val rel = hybrid(mapOf(1L to Lex(0.5, 2)), mapOf(1L to 0.75, 2L to 0.9), listOf(1L, 2L, 3L))
        assertEquals(0.4 * 0.5 + 0.6 * 1.0, rel.getValue(1), 1e-9)
        assertEquals(0.6 * 1.0, rel.getValue(2), 1e-9)
        assertEquals(0.0, rel.getValue(3), 1e-9)
        assertEquals(mapOf(1L to 0.5), hybrid(mapOf(1L to Lex(0.5, 2)), null, listOf(1L)))
    }

    @Test
    fun vectorsRoundTrip() {
        val v = floatArrayOf(0.5f, -0.25f, 3f)
        assertTrue(v.contentEquals(Vectors.unpack(Vectors.pack(v))))
    }

    @Test
    fun spreadActivatesLinkedMemories() {
        val (rel, assoc) = spread(mapOf(1L to 0.9, 2L to 0.0), mapOf(1L to listOf(2L to 0.8), 2L to listOf(1L to 0.8)))
        assertEquals(0.6 * 0.8 * 0.9, rel.getValue(2), 1e-9)
        assertEquals(setOf(2L), assoc)
        assertEquals(mapOf(1L to 0.3) to emptySet<Long>(), spread(mapOf(1L to 0.3), null))
    }

    @Test
    fun keysScoresMatchesWholeTermsOnly() {
        val docs = mapOf(1L to "年糕 糕生 生病", 2L to "吉他 学吉")
        val scores = keysScores(docs, "年糕生病了吗", corpusSize = 100)
        assertEquals(setOf(1L), scores.keys)
        assertTrue(scores.getValue(1).hits >= 2)
        assertTrue(keysScores(docs, "").isEmpty())
    }

    @Test
    fun recallableNeedsRelevanceAndMatches() {
        assertFalse(recallable(0.4, 5, null))
        assertTrue(recallable(0.5, 2, null))
        assertFalse(recallable(0.5, 1, null))
        assertTrue(recallable(0.5, 1, 0.7))
    }

    @Test
    fun wantsEpisodesOnPastCues() {
        assertTrue(wantsEpisodes("你还记得我养的猫吗", null))
        assertTrue(wantsEpisodes("do you remember my cat", null))
        assertFalse(wantsEpisodes("今天吃什么", null))
    }

    @Test
    fun keywordSearchSplitsShortTermsToLike() {
        val terms = KeywordSearch.split("年糕")
        assertEquals(listOf("年糕"), terms.like)
        assertTrue(terms.match.isEmpty())
        val mixed = KeywordSearch.split("guitar 年糕生病 ok")
        assertTrue("guitar" in mixed.match)
        assertTrue("年糕生" in mixed.match)
        assertTrue("年糕" in mixed.like)
        assertTrue("ok" in mixed.like)
        assertEquals("\"a\"\"b\" OR \"cd\"", KeywordSearch.matchExpression(listOf("a\"b", "cd")))
        assertEquals("%50\\%\\_%", KeywordSearch.likePattern("50%_"))
        assertNull(null)
    }
}
