package org.xmyun.starmate.feature.memory

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmyun.starmate.core.model.HeadlessResult
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.LlmException
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.memory.consolidate.Consolidator.Outcome
import org.xmyun.starmate.feature.memory.consolidate.NewMemory
import org.xmyun.starmate.feature.memory.model.Categories
import org.xmyun.starmate.feature.memory.text.estimateTokens

@RunWith(RobolectricTestRunner::class)
class ConsolidatorTest {
    private val graph = MemoryGraph()
    private val repo = graph.repo
    private val consolidator = graph.consolidator

    @After
    fun tearDown() = graph.close()

    private suspend fun chat(user: String, reply: String, ts: Long = NOW) = repo.addEpisode(ts, user, reply, "CHAT")

    @Test
    fun successfulRunAppliesOpsWritesGistAndAdvances() = runTest {
        chat("我下周三要面试产品经理，好紧张", "你一定可以的！")
        val last = chat("面试完我想去吃火锅", "我陪你庆祝")
        graph.engine.responder = {
            FakeEngine.ok(
                """好的
                ```json
                {"ops":[
                  {"op":"add","content":"小明 2026-11-04 面试产品经理，很紧张","category":"event","importance":3,"emotion":2,"tags":"面试 工作","date":"2026-11-04","turn":1},
                  {"op":"add","content":"我答应面试后陪小明吃火锅庆祝","category":"self","importance":4,"turn":2}
                ],"gist":"小明说下周三面试，有点紧张，约好面试后吃火锅"}
                ```""",
            )
        }
        val outcome = consolidator.consolidateOnce()
        assertTrue(outcome is Outcome.Done)
        val call = graph.engine.calls.single()
        assertEquals(TurnPurpose.MEMORY, call.purpose)
        assertEquals(ModelRole.BACKGROUND, call.role)
        assertTrue(call.jsonOutput)
        assertEquals(TestPrompts.zh["consolidate_system"], call.systemOverride)
        assertTrue(call.instruction.contains("我下周三要面试产品经理"))
        val memories = repo.allMemories()
        assertEquals(2, memories.size)
        assertTrue(memories.any { it.category == Categories.SELF })
        assertNotNull(memories.first { it.category == Categories.EVENT }.eventAt)
        assertEquals("小明说下周三面试，有点紧张，约好面试后吃火锅", repo.recentGists().single().summary)
        assertEquals(last, repo.state().consolidatedUpto)
        assertEquals(0, consolidator.pendingCount())
        // 事实反哺：第一轮原话能被新关键词“工作”找到
        assertTrue(repo.keywordCandidates("工作").isNotEmpty())
    }

    @Test
    fun factChangeInvalidatesOldMemoryInsteadOfDeleting() = runTest {
        val old = repo.addMemory(NewMemory("小明住在上海", category = Categories.PROFILE, importance = 4), NOW - 1000)!!
        chat("我上个月从上海搬到北京了", "新家怎么样")
        graph.engine.responder = {
            FakeEngine.ok("""{"ops":[{"op":"invalidate","id":$old,"turn":1},{"op":"add","content":"小明搬到了北京","category":"profile","importance":4,"turn":1}],"gist":"小明搬家到北京"}""")
        }
        consolidator.consolidateOnce()
        val m = repo.memory(old)!!
        assertTrue(!m.isValid && m.archived)
        assertTrue(repo.allMemories().any { it.content == "小明搬到了北京" && it.isValid })
    }

    @Test
    fun budgetExceededKeepsPendingEpisodesAndDoesNotRetry() = runTest {
        chat("今天好累", "抱抱")
        chat("想早点睡", "晚安")
        graph.engine.responder = { HeadlessResult.BudgetExceeded }
        assertEquals(Outcome.Budget, consolidator.consolidatePending())
        assertEquals(1, graph.engine.calls.size)
        assertEquals(2, consolidator.pendingCount())
        assertEquals(0L, repo.state().consolidatedUpto)
        assertTrue(repo.allMemories().isEmpty())
        assertEquals(2, repo.countEpisodes())
    }

    @Test
    fun threeFailuresOnSameEpisodeSkipItAndContinue() = runTest {
        val poison = chat("POISON 这一段让模型出错", "嗯")
        chat("我养了一只猫叫团子", "好可爱")
        val last = chat("团子今天打翻了杯子", "哈哈")
        graph.engine.responder = { turn ->
            if (turn.instruction.contains("POISON")) {
                FakeEngine.ok("这不是 JSON")
            } else {
                FakeEngine.ok("""{"ops":[{"op":"add","content":"小明养了一只猫叫团子","category":"fact","turn":1}],"gist":"聊到猫团子"}""")
            }
        }
        // 整批失败先缩小批次，再对单独那一段连续失败三次
        assertTrue(consolidator.consolidateOnce() is Outcome.Failed)
        assertTrue(consolidator.consolidateOnce() is Outcome.Failed)
        assertTrue(consolidator.consolidateOnce() is Outcome.Failed)
        assertEquals(Outcome.Skipped(poison), consolidator.consolidateOnce())
        assertTrue(graph.diag.contains("#$poison"))
        assertTrue(consolidator.consolidatePending() is Outcome.Done)
        assertEquals(last, repo.state().consolidatedUpto)
        assertNotNull(repo.episode(poison)) // 原话保留
        assertTrue(repo.allMemories().any { it.content == "小明养了一只猫叫团子" })
    }

    @Test
    fun modelOutageDoesNotSkipContent() = runTest {
        chat("你好", "你好呀")
        graph.engine.responder = { HeadlessResult.Failed(LlmException(LlmErrorKind.UNREACHABLE, "offline")) }
        repeat(5) { assertTrue(consolidator.consolidateOnce() is Outcome.Failed) }
        assertEquals(0L, repo.state().consolidatedUpto)
        assertEquals(1, consolidator.pendingCount())
    }

    @Test
    fun inputIsCapped() = runTest {
        repeat(30) { chat("很长的一段话".repeat(400), "回复".repeat(300)) }
        repeat(50) { repo.addMemory(NewMemory("核心记忆$it" + "长".repeat(60), importance = 5), NOW) }
        graph.engine.responder = { FakeEngine.ok("""{"ops":[],"gist":"聊了很多"}""") }
        consolidator.consolidateOnce()
        val instruction = graph.engine.calls.first().instruction
        val cap = graph.settings.maxBatchTokens + graph.settings.consolidateContextTokens + 500
        assertTrue("instruction too long: ${estimateTokens(instruction)}", estimateTokens(instruction) <= cap)
        assertTrue(consolidator.pendingCount() in 1..29)
    }

    @Test
    fun clearDuringModelCallDiscardsResult() = runTest {
        chat("我叫小明", "你好小明")
        graph.engine.responder = {
            kotlinx.coroutines.runBlocking { repo.clearAll() }
            FakeEngine.ok("""{"ops":[{"op":"add","content":"用户叫小明","category":"profile","turn":1}],"gist":"自我介绍"}""")
        }
        assertEquals(Outcome.Stale, consolidator.consolidateOnce())
        assertTrue(repo.allMemories().isEmpty())
    }
}
