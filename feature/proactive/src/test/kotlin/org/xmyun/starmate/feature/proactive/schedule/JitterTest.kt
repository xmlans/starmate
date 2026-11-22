package org.xmyun.starmate.feature.proactive.schedule

import java.time.Duration
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmyun.starmate.feature.proactive.FixedRandom
import org.xmyun.starmate.feature.proactive.local
import org.xmyun.starmate.feature.proactive.platform.RandomSource

class JitterTest {

    private val now = local("2026-11-12T10:00")
    private val target = local("2026-11-13T18:00")
    private val max = Duration.ofMinutes(15)

    @Test
    fun offsetStaysWithinFifteenMinutes() {
        val random = object : RandomSource {
            val rng = Random(42)
            override fun nextLong(from: Long, until: Long): Long = rng.nextLong(from, until)
        }
        repeat(2_000) {
            val result = Jitter.apply(target, now, random)
            val offset = Duration.between(target, result).abs()
            assertTrue("offset $offset", offset <= max)
        }
    }

    @Test
    fun extremesAreExactlyFifteenMinutes() {
        assertEquals(target.minus(max), Jitter.apply(target, now, FixedRandom(Long.MIN_VALUE)))
        assertEquals(target.plus(max), Jitter.apply(target, now, FixedRandom(Long.MAX_VALUE)))
    }

    @Test
    fun resultIsNeverBeforeNow() {
        val soon = now.plus(Duration.ofMinutes(5))
        val result = Jitter.apply(soon, now, FixedRandom(Long.MIN_VALUE))
        assertFalse(result.isBefore(now))
        assertEquals(now, result)
    }

    @Test
    fun afterOnlyShiftsForward() {
        assertEquals(target, Jitter.after(target, FixedRandom(Long.MIN_VALUE)))
        assertEquals(target.plus(max), Jitter.after(target, FixedRandom(Long.MAX_VALUE)))
    }
}
