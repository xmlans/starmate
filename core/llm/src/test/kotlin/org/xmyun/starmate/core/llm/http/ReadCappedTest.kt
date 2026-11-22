package org.xmyun.starmate.core.llm.http

import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadCappedTest {

    @Test
    fun readsWholeStreamWhenItIsShort() {
        val bytes = "{\"error\":{\"message\":\"bad key\"}}".toByteArray()
        assertArrayEquals(bytes, readCapped(bytes.inputStream(), limit = 1024))
    }

    @Test
    fun stopsAtTheLimit() {
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        val read = readCapped(bytes.inputStream(), limit = 4096)
        assertArrayEquals(bytes.copyOf(4096), read)
    }

    @Test
    fun doesNotKeepReadingAnEndlessStream() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                served += len
                return len
            }
        }
        val read = readCapped(endless, limit = MAX_ERROR_BODY_BYTES)
        assertEquals(MAX_ERROR_BODY_BYTES, read.size)
        // 只向流要了上限那么多，没有多读
        assertEquals(MAX_ERROR_BODY_BYTES.toLong(), served)
    }
}
