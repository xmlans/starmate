package org.xmyun.starmate.feature.memory.consolidate

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.feature.memory.InMemoryOpsStore
import org.xmyun.starmate.feature.memory.ZONE
import org.xmyun.starmate.feature.memory.model.Categories
import org.xmyun.starmate.feature.memory.model.Sources
import org.xmyun.starmate.feature.memory.retrieval.MemoryTime

class OperationsTest {
    private val store = InMemoryOpsStore()
    private val applier = OperationsApplier(store, ZONE)

    private fun ops(vararg json: String): List<JsonObject> = json.map { Json.parseToJsonElement(it).jsonObject }

    @Test
    fun addUpdateMergeArchiveDelete() = runTest {
        val a = store.add("喜欢猫") { it.copy(category = Categories.PREFERENCE) }
        val b = store.add("养了一只橘猫") { it.copy(category = Categories.FACT, importance = 4) }
        val c = store.add("明天考试") { it.copy(category = Categories.EVENT, importance = 2) }
        val e = store.add("说过一次天气不错") { it.copy(importance = 1) }
        val stats = applier.apply(
            ops(
                """{"op":"add","content":"名字叫小明","category":"profile","importance":5,"tags":"姓名","date":"2026-05-03"}""",
                """{"op":"update","id":$b,"content":"养了一只叫团子的橘猫"}""",
                """{"op":"merge","ids":[$a,$b],"content":"喜欢猫，养了一只叫团子的橘猫"}""",
                """{"op":"archive","id":$e}""",
                """{"op":"delete","id":"#$c"}""",
                """{"op":"delete","id":999}""",
            ),
            now = 10,
        )
        assertEquals(ApplyStats(add = 1, update = 1, merge = 1, archive = 1, delete = 1), stats)
        val merged = store.memories.getValue(a)
        assertEquals("喜欢猫，养了一只叫团子的橘猫" to 4, merged.content to merged.importance)
        assertNull(store.memories[b])
        assertTrue(store.memories.getValue(e).archived)
        val name = store.memories.values.first { it.content == "名字叫小明" }
        assertEquals("2026-05-03", MemoryTime.fmtDate(name.eventAt, ZONE))
    }

    @Test
    fun invalidateKeepsHistoryInsteadOfDeleting() = runTest {
        val old = store.add("住在上海") { it.copy(category = Categories.PROFILE) }
        applier.apply(ops("""{"op":"invalidate","id":$old}""", """{"op":"add","content":"搬到了北京","category":"profile"}"""), now = 50)
        val m = store.memories.getValue(old)
        assertTrue(m.archived && !m.isValid)
        assertEquals(50L, m.invalidAt)
        // 已失效的不能再被 update，也不会被“想起”后重新激活
        applier.apply(ops("""{"op":"update","id":$old,"content":"x"}"""), now = 60)
        store.touch(listOf(old), 70)
        assertTrue(store.memories.getValue(old).archived)
        assertEquals("住在上海", store.memories.getValue(old).content)
        assertNotNull(store.memories.values.firstOrNull { it.content == "搬到了北京" })
    }

    @Test
    fun duplicateAddReinforcesAndRevives() = runTest {
        val a = store.add("喜欢吃麻辣火锅") { it.copy(archived = true) }
        val stats = applier.apply(ops("""{"op":"add","content":"喜欢吃麻辣火锅"}"""), now = 5)
        assertEquals(0, stats.add)
        val m = store.memories.getValue(a)
        assertFalse(m.archived)
        assertEquals(1, m.hits)
    }

    @Test
    fun pinnedIsProtected() = runTest {
        val a = store.add("核心设定") { it.copy(pinned = true) }
        val b = store.add("别的")
        applier.apply(
            ops(
                """{"op":"delete","id":$a}""", """{"op":"archive","id":$a}""", """{"op":"invalidate","id":$a}""",
                """{"op":"merge","ids":[$a,$b],"content":"x"}""",
            ),
            now = 1,
        )
        assertEquals(2, store.memories.size)
        assertFalse(store.memories.getValue(a).archived)
        assertEquals(b, archiveOverflow(store, 0, 0, 30.0).single().id)
    }

    @Test
    fun allowedIdsLimitEdits() = runTest {
        val a = store.add("甲事")
        val b = store.add("乙事")
        applier.apply(ops("""{"op":"archive","id":$a}""", """{"op":"archive","id":$b}"""), now = 1, allowedIds = setOf(a))
        assertTrue(store.memories.getValue(a).archived)
        assertFalse(store.memories.getValue(b).archived)
    }

    @Test
    fun maxImportanceAndSource() = runTest {
        applier.apply(ops("""{"op":"add","content":"碎片","importance":5}"""), now = 1, source = Sources.AI, maxImportance = 4)
        val m = store.memories.values.single()
        assertEquals(4 to Sources.AI, m.importance to m.source)
    }

    @Test
    fun mergeKeepsHighestEmotion() = runTest {
        val deep = store.add("和初恋分手了") { it.copy(emotion = 3) }
        val other = store.add("分手后一周没睡好")
        applier.apply(ops("""{"op":"merge","ids":[$other,$deep],"content":"和初恋分手，一周没睡好"}"""), now = 1)
        assertEquals(3, store.memories.getValue(other).emotion)
    }

    @Test
    fun autoAndExplicitLinksAndRelink() = runTest {
        val a = store.add("小雨在字节实习做后端")
        val stats = applier.apply(
            ops(
                """{"op":"add","content":"小雨在字节实习时住在徐汇","related":[$a]}""",
                """{"op":"add","content":"小雨实习做后端开发很累"}""",
            ),
            now = 1,
        )
        assertEquals(2, stats.add)
        val links = store.links()
        assertTrue(a in links.getValue(a + 1).map { it.first })
        assertTrue(links[a + 2].orEmpty().isNotEmpty())
        applier.apply(ops("""{"op":"merge","ids":[$a,${a + 1}],"content":"小雨在字节实习，住徐汇"}"""), now = 2)
        assertFalse((a + 1) in store.links())
        store.deleteMemory(a)
        assertNull(store.links()[a])
    }

    @Test
    fun factFeedsBackIntoEpisodeKeys() = runTest {
        val episode = store.addEpisode("我周末去爬了黄山", "真棒")
        applier.apply(
            ops("""{"op":"add","content":"小明喜欢户外运动","tags":"登山 徒步","turn":1}"""),
            now = 1,
            provenance = Provenance(listOf(episode)),
        )
        assertTrue("徒步" in store.episodes.getValue(episode.id).keys.split(' '))
        assertEquals(episode.id, store.memories.values.single().episodeId)
    }

    @Test
    fun oneBadOperationDoesNotStopTheRest() = runTest {
        val stats = applier.apply(ops("""{"op":"add","content":""}""", """{"op":"unknown"}""", """{"op":"add","content":"有效的"}"""), now = 1)
        assertEquals(1, stats.add)
    }

    @Test
    fun parserToleratesFencesAndRejectsBadStructure() {
        assertEquals(Json.parseToJsonElement("""{"ops":[]}"""), OperationsParser.parseObject("好的：\n```json\n{\"ops\": []}\n```"))
        assertNull(OperationsParser.parseObject("not json"))
        assertTrue(OperationsParser.validOperations(OperationsParser.parseObject("""{"ops":[],"gist":"聊了天"}""")))
        assertFalse(OperationsParser.validOperations(OperationsParser.parseObject("""{"gist":"x"}""")))
        assertFalse(OperationsParser.validOperations(OperationsParser.parseObject("""{"ops":[{"op":"add"}]}""")))
        assertFalse(OperationsParser.validOperations(OperationsParser.parseObject("""{"ops":[{"op":"update","id":0,"content":"x"}]}""")))
        assertFalse(OperationsParser.validOperations(OperationsParser.parseObject("""{"ops":[{"op":"merge","ids":[1],"content":"x"}]}""")))
        assertFalse(OperationsParser.validOperations(OperationsParser.parseObject("""{"ops":[{"op":"add","content":"x","importance":9}]}""")))
        assertFalse(OperationsParser.validOperations(OperationsParser.parseObject("""{"ops":[{"op":"add","content":"x","related":["a"]}]}""")))
        assertFalse(OperationsParser.validOperations(null))
    }
}
