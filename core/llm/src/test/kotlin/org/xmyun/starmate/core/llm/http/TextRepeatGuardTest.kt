package org.xmyun.starmate.core.llm.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRepeatGuardTest {

    private val reply = "我猜……是自己想做的小东西？那种「反正是自己的，怎么折腾都不亏」的项目。"

    @Test
    fun ordinaryDeltasPassThrough() {
        val guard = TextRepeatGuard()
        assertEquals("我猜……", guard.delta("我猜……"))
        assertEquals("是自己想做的", guard.delta("是自己想做的"))
        assertFalse(guard.repeated)
    }

    @Test
    fun deltaThatResendsEverythingKeepsOnlyTheNewEnd() {
        val guard = TextRepeatGuard()
        guard.delta(reply.substring(0, 12))
        guard.delta(reply.substring(12))
        // 服务端在结尾把整段又发了一遍
        assertEquals("", guard.delta(reply))
        assertEquals("猜中了没？", guard.delta(reply + "猜中了没？").let { it })
        assertTrue(guard.repeated)
    }

    @Test
    fun shortTextIsNotTreatedAsResent() {
        // 前面只有一个字时，以它开头的增量很常见，不能当成重发
        val guard = TextRepeatGuard()
        guard.delta("哈")
        assertEquals("哈哈哈", guard.delta("哈哈哈"))
        assertFalse(guard.repeated)
    }

    @Test
    fun snapshotWithoutEarlierDeltasIsTheWholeReply() {
        val guard = TextRepeatGuard()
        assertEquals(reply, guard.snapshot(reply))
        assertFalse(guard.repeated)
    }

    @Test
    fun snapshotAfterDeltasAddsOnlyWhatIsMissing() {
        val guard = TextRepeatGuard()
        guard.delta(reply)
        assertEquals("", guard.snapshot(reply))
        assertEquals("猜中了没？", guard.snapshot(reply + "猜中了没？"))
        assertTrue(guard.repeated)
    }

    @Test
    fun snapshotThatDoesNotMatchIsDropped() {
        val guard = TextRepeatGuard()
        guard.delta(reply)
        assertEquals("", guard.snapshot("完全不一样的一段话，和前面对不上，所以不能接上去。"))
    }
}
