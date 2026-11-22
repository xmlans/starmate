package org.xmyun.starmate.core.data.diag

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.xmyun.starmate.core.data.MutableClock

class RingDiagLogTest {

    private val clock = MutableClock(Instant.parse("2026-11-11T00:00:00Z"))

    @Test
    fun keepsLatest500() {
        val log = RingDiagLog(clock)
        repeat(600) { log.log("t", "m$it") }
        val entries = log.entries.value
        assertEquals(500, entries.size)
        assertEquals("m100", entries.first().message)
        assertEquals("m599", entries.last().message)
    }

    @Test
    fun redactsBeforeStoring() {
        val log = RingDiagLog(clock)
        log.log("llm", "POST https://api/x?key=SECRET123 Authorization: Bearer abcdef123456")
        val message = log.entries.value.single().message
        assertFalse(message.contains("SECRET123"))
        assertFalse(message.contains("abcdef123456"))
    }

    @Test
    fun usesInjectedClock() {
        val log = RingDiagLog(clock)
        log.log("t", "m")
        assertEquals(clock.now, log.entries.value.single().at)
    }
}
