package org.xmyun.starmate.feature.memory

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.model.Role

@RunWith(RobolectricTestRunner::class)
class ForgetReplyTest {
    private val graph = MemoryGraph()
    private val repo = graph.repo
    private val writer = DefaultMemoryWriter(repo, graph.clock)

    @After
    fun tearDown() = graph.close()

    private val offensive = "这是一段被用户举报的冒犯性回复，里面提到了萝卜炖排骨"

    @Test
    fun reportedReplyIsBlankedAndNoLongerSearchable() = runTest {
        val id = repo.addEpisode(NOW, "晚饭吃什么好", offensive, "CHAT")
        repo.addEpisode(NOW + 1000, "明天下雨吗", "明天多云，不下雨", "CHAT")
        assertTrue(id in repo.keywordCandidates("萝卜炖排骨"))

        writer.forgetReply(chatMessage(7, Role.ASSISTANT, offensive, NOW))

        val episode = repo.episode(id)!!
        // 用户说的那一句留着，她的回复清空
        assertEquals("晚饭吃什么好", episode.userText)
        assertEquals("", episode.replyText)
        assertFalse(id in repo.keywordCandidates("萝卜炖排骨"))
        // 用户那一句仍然找得到
        assertTrue(id in repo.keywordCandidates("晚饭吃什么"))
        // 别的原话不受影响
        assertEquals("明天多云，不下雨", repo.recentEpisodes(10).last().replyText)
    }

    @Test
    fun unrelatedOrBlankReplyChangesNothing() = runTest {
        repo.addEpisode(NOW, "晚饭吃什么好", offensive, "CHAT")
        assertEquals(0, repo.redactReply("完全不一样的一句话"))
        assertEquals(0, repo.redactReply("   "))
        assertEquals(offensive, repo.recentEpisodes(10).single().replyText)
    }
}
