package org.xmyun.starmate.feature.memory

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.model.ATTACHMENT_PREVIEW_KEY
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.AttachmentTypes
import org.xmyun.starmate.core.model.CompletedTurn
import org.xmyun.starmate.core.model.EmbeddingClient
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.memory.consolidate.NewMemory
import org.xmyun.starmate.feature.memory.model.Categories
import org.xmyun.starmate.feature.memory.model.DAY_MS
import org.xmyun.starmate.feature.memory.text.estimateTokens
import org.xmyun.starmate.feature.memory.work.ConsolidateScheduler
import org.xmyun.starmate.feature.memory.work.ConsolidateTrigger

@RunWith(RobolectricTestRunner::class)
class PromptContributorTest {
    private val graph = MemoryGraph()
    private val repo = graph.repo
    private val contributor = graph.contributor

    @After
    fun tearDown() = graph.close()

    @Test
    fun stableSnapshotIsByteIdenticalWhileMemoriesUnchanged() = runTest {
        val a = repo.addMemory(NewMemory("小明喜欢猫", category = Categories.PREFERENCE), NOW)!!
        repo.addMemory(NewMemory("我答应给小明推荐小说", category = Categories.SELF, importance = 4), NOW)
        repo.setDigest("小明是程序员，养猫")
        val first = contributor.stable(TurnPurpose.CHAT)!!
        assertTrue(first.contains("小明喜欢猫"))
        assertTrue(first.contains("【我们之间】"))
        repo.touch(listOf(a), NOW + 1)
        graph.clock.now = NOW + 10 * DAY_MS
        assertEquals(first, contributor.stable(TurnPurpose.CHAT))
        repo.addMemory(NewMemory("小明在学吉他"), NOW + 10 * DAY_MS)
        assertNotEquals(first, contributor.stable(TurnPurpose.CHAT))
    }

    @Test
    fun onlyChatProactiveAndDiaryParticipate() = runTest {
        repo.addMemory(NewMemory("小明喜欢猫"), NOW)
        assertNotNull(contributor.stable(TurnPurpose.CHAT))
        assertNotNull(contributor.stable(TurnPurpose.PROACTIVE))
        assertNotNull(contributor.stable(TurnPurpose.DIARY))
        assertNull(contributor.stable(TurnPurpose.ONBOARDING))
        assertNull(contributor.stable(TurnPurpose.MEMORY))
        assertNull(contributor.dynamic(TurnPurpose.ONBOARDING, "猫"))
        assertNull(contributor.dynamic(TurnPurpose.MEMORY, "猫"))
        // 记忆工具的说明只在聊天里出现
        assertTrue(contributor.stable(TurnPurpose.CHAT)!!.contains("remember_fragment"))
        assertFalse(contributor.stable(TurnPurpose.PROACTIVE)!!.contains("remember_fragment"))
    }

    @Test
    fun dynamicRecallStaysInBudgetAndSkipsEpisodesAlreadyInContext() = runTest {
        repeat(40) { repo.addMemory(NewMemory("橘猫的第${it}件趣事" + "很长的描述".repeat(10)), NOW - 200 * DAY_MS) }
        repo.allMemories().forEach { repo.archiveMemory(it.id) }
        val crowded = contributor.dynamic(TurnPurpose.CHAT, "橘猫趣事")!!
        assertTrue(crowded.contains("橘猫"))
        assertTrue(estimateTokens(crowded) <= graph.settings.recallInjectTokens)

        repo.addEpisode(NOW - 60 * DAY_MS, "团子小时候很胆小", "现在胆子大多了", "CHAT")
        repo.addEpisode(NOW - 60000, "团子今天打翻了杯子", "哈哈它好调皮", "CHAT")
        graph.messages.messages += chatMessage(1, Role.USER, "团子今天打翻了杯子", NOW - 60000)
        graph.messages.messages += chatMessage(2, Role.ASSISTANT, "哈哈它好调皮", NOW - 59000)
        val text = contributor.dynamic(TurnPurpose.CHAT, "你还记得团子小时候吗")!!
        assertTrue(estimateTokens(text) <= graph.settings.recallInjectTokens)
        assertTrue(text.contains("团子小时候很胆小"))
        assertFalse(text.contains("打翻了杯子"))
    }

    @Test
    fun careHintOnlyOnceWithinThreeDays() = runTest {
        repo.addMemory(NewMemory("小明明天面试产品经理", category = Categories.EVENT, eventAt = NOW + DAY_MS / 2), NOW - DAY_MS)
        val first = contributor.dynamic(TurnPurpose.CHAT, "在吗")
        assertTrue(first!!.contains("<memory_care>"))
        assertTrue(first.contains("面试产品经理"))
        // 这一轮失败了（没有提交）：重试时提醒还在
        val retry = contributor.dynamic(TurnPurpose.CHAT, "在吗")
        assertTrue(retry!!.contains("<memory_care>"))
        // 这一轮成功后才算提过，之后 3 天内不再出现
        repo.commitStagedNudge()
        val second = contributor.dynamic(TurnPurpose.CHAT, "在吗")
        assertFalse(second.orEmpty().contains("<memory_care>"))
        // 关心提示不进稳定部分
        assertFalse(contributor.stable(TurnPurpose.CHAT)!!.contains("memory_care"))
    }

    @Test
    fun embeddingFailureFallsBackToKeywords() = runTest {
        val id = repo.addMemory(NewMemory("团子是一只橘猫", tags = "宠物 猫咪"), NOW - 200 * DAY_MS)!!
        repo.archiveMemory(id)
        repo.putVectors("m", listOf(id to floatArrayOf(1f, 0f)), mapOf(id to repo.memory(id)!!.text)) { repo.memory(it)?.text }
        graph.router.embeddingClient = object : EmbeddingClient {
            override val modelId = "broken"
            override suspend fun embed(texts: List<String>): List<FloatArray> = throw IllegalStateException("boom")
        }
        val text = contributor.dynamic(TurnPurpose.CHAT, "我家宠物团子最近怎么样")
        assertTrue(text.orEmpty().contains("团子是一只橘猫"))
        assertTrue(graph.diag.contains("embedding failed"))
    }

    @Test
    fun semanticRecallWithEmbeddings() = runTest {
        val id = repo.addMemory(NewMemory("小明对花生过敏"), NOW - 200 * DAY_MS)!!
        repo.archiveMemory(id)
        graph.router.embeddingClient = FakeEmbedding { text ->
            if ("过敏" in text || "不能吃" in text) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
        }
        graph.vectors.index()
        val text = contributor.dynamic(TurnPurpose.CHAT, "有什么东西是我不能吃的")
        assertTrue(text.orEmpty().contains("花生过敏"))
    }
}

@RunWith(RobolectricTestRunner::class)
class TurnListenerTest {
    private val graph = MemoryGraph()

    @After
    fun tearDown() = graph.close()

    private class CountingTrigger : ConsolidateTrigger {
        var count = 0
        override fun requestNow(maintain: Boolean) {
            count++
        }
    }

    @Test
    fun storesEveryTurnIncludingProactiveAndVoiceTranscripts() = runTest {
        val scheduler = ConsolidateScheduler(appContext(), FakeAppState(), graph.settings, graph.clock)
        val listener = MemoryTurnListener(graph.repo, scheduler, graph.settings, graph.diag)
        val trigger = CountingTrigger()
        listener.trigger = trigger
        val voice = Attachment(AttachmentTypes.VOICE, buildJsonObject { put(ATTACHMENT_PREVIEW_KEY, JsonPrimitive("今天也要开心哦")) })
        val reply = chatMessage(2, Role.ASSISTANT, "", NOW).copy(attachments = listOf(voice))
        listener.onTurnCompleted(CompletedTurn(TurnPurpose.CHAT, chatMessage(1, Role.USER, "早安", NOW), reply, emptyList()))
        val proactive = chatMessage(3, Role.ASSISTANT, "面试怎么样啦？", NOW + 1000, TurnPurpose.PROACTIVE)
        listener.onTurnCompleted(CompletedTurn(TurnPurpose.PROACTIVE, null, proactive, emptyList()))
        val episodes = graph.repo.recentEpisodes(10)
        assertEquals("早安" to "今天也要开心哦", episodes[0].userText to episodes[0].replyText)
        assertEquals("" to "面试怎么样啦？", episodes[1].userText to episodes[1].replyText)
        assertEquals("PROACTIVE", episodes[1].purpose)
        assertEquals(0, trigger.count)
        repeat(4) { listener.onTurnCompleted(CompletedTurn(TurnPurpose.CHAT, chatMessage(10L + it, Role.USER, "第$it 句", NOW), chatMessage(20L + it, Role.ASSISTANT, "嗯", NOW), emptyList())) }
        assertEquals(1, trigger.count)
    }
}
