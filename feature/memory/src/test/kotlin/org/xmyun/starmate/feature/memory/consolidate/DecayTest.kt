package org.xmyun.starmate.feature.memory.consolidate

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.feature.memory.InMemoryOpsStore
import org.xmyun.starmate.feature.memory.model.DAY_MS
import org.xmyun.starmate.feature.memory.model.Mem

class DecayTest {
    private val store = InMemoryOpsStore()

    @Test
    fun halfLifeAndReinforcement() = runTest {
        val a = store.add("常用")
        val b = store.add("冷门")
        repeat(5) { store.touch(listOf(a), 0) }
        val ma = store.memories.getValue(a)
        val mb = store.memories.getValue(b)
        assertTrue(ma.strength(60 * DAY_MS, 30.0) > mb.strength(60 * DAY_MS, 30.0))
        assertEquals(0.5, mb.decay(30 * DAY_MS, 30.0), 1e-9)
        assertEquals(0.25, mb.decay(60 * DAY_MS, 30.0), 1e-9)
    }

    @Test
    fun emotionSlowsForgetting() {
        val calm = Mem(1, "去超市买了牛奶")
        val deep = Mem(2, "和初恋分手了", emotion = 3)
        assertTrue(deep.decay(60 * DAY_MS, 30.0) > calm.decay(60 * DAY_MS, 30.0) * 2)
    }

    @Test
    fun pinnedNeverFades() {
        assertEquals(Double.POSITIVE_INFINITY, Mem(1, "核心", pinned = true).strength(1000 * DAY_MS, 30.0), 0.0)
    }

    @Test
    fun overflowArchivesThenArchiveLimitForgets() = runTest {
        store.add("重要") { it.copy(importance = 5) }
        store.add("琐碎") { it.copy(importance = 1) }
        store.add("新的一般", now = 100 * DAY_MS) { it.copy(importance = 3) }
        val moved = archiveOverflow(store, 2, 100 * DAY_MS, 30.0)
        assertEquals(listOf("琐碎"), moved.map { it.content })
        // 归档没超上限时不遗忘
        assertTrue(trimArchive(store, 1, 100 * DAY_MS, 30.0).isEmpty())
        trimArchive(store, 0, 100 * DAY_MS, 30.0)
        assertEquals(0, store.memories.values.count { it.archived })
        assertEquals(2, store.memories.values.count { !it.archived })
    }

    @Test
    fun invalidFactsAreForgottenFirst() = runTest {
        val a = store.add("旧住址") { it.copy(importance = 4) }
        val b = store.add("一般事实") { it.copy(importance = 2) }
        store.invalidateMemory(a, 0)
        store.archiveMemory(b)
        val dropped = trimArchive(store, 1, DAY_MS, 30.0)
        assertEquals(listOf(a), dropped.map { it.id })
    }

    @Test
    fun archiveFadedOnlyLowImportance() = runTest {
        store.add("琐碎旧事") { it.copy(importance = 1) }
        store.add("重要旧事") { it.copy(importance = 4) }
        archiveFaded(store, 365 * DAY_MS, 30.0)
        assertEquals(listOf("重要旧事"), store.memories.values.filter { !it.archived }.map { it.content })
    }
}
