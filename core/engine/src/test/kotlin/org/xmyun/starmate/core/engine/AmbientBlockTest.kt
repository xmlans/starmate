@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.xmyun.starmate.core.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientBlockTest {

    @Test
    fun firstLineIsLocalDateWeekdayAndTime() = runTest {
        val block = AmbientBlock(emptySet(), MutableClock())
        assertEquals("现在是 2026年11月11日 星期三 21:05", block.render(chinese = true))
    }

    @Test
    fun slowThrowingAndEmptySourcesAreIgnored() = runTest {
        val sources = setOf(
            FakeAmbientSource("a_weather") { "小雨 12°C" },
            FakeAmbientSource("b_slow") { delay(5_000); "太慢了" },
            FakeAmbientSource("c_boom") { error("权限被拒") },
            FakeAmbientSource("d_null") { null },
            FakeAmbientSource("e_blank") { "  " },
            FakeAmbientSource("f_steps") { "今天步数 3200" },
        )
        val text = AmbientBlock(sources, MutableClock()).render(chinese = true)
        val lines = text.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("现在是"))
        assertEquals("小雨 12°C", lines[1])
        assertEquals("今天步数 3200", lines[2])
        // 慢的来源最多等 2 秒
        assertTrue(currentTime <= AmbientBlock.SNAPSHOT_TIMEOUT_MS)
    }
}
