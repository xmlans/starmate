package org.xmyun.starmate.core.llm.http

/**
 * 防止服务端把已经发过的回复文字再发一遍。有的中转站在流的结尾补一个带整段回复的数据块
 * （只有 `message` 没有 `delta`，或者一个内容是整段回复的 `delta`），原样接上去就会在气泡里看到同一段话两遍。
 *
 * 只认“新内容以已发出的全部文字开头”这一种形态，且已发出的文字至少 [MIN_REPEAT_CHARS] 个字符：
 * 正常的增量很短，不可能恰好以前面的全部文字开头，所以不会误伤。
 */
internal class TextRepeatGuard {

    private val emitted = StringBuilder()

    /** 这次回复里有没有丢掉过重发的文字。 */
    var repeated = false
        private set

    /** 一段增量文字。正常时原样返回；它把已发出的整段又带了一遍时，只返回后面新增的部分（可能为空）。 */
    fun delta(text: String): String {
        if (emitted.length >= MIN_REPEAT_CHARS && text.length >= emitted.length && text.startsWith(emitted)) {
            repeated = true
            val rest = text.substring(emitted.length)
            emitted.append(rest)
            return rest
        }
        emitted.append(text)
        return text
    }

    /**
     * 一份整段回复（例如只带 `message` 的数据块）。还没发过文字时原样返回；
     * 已经发过时只返回它比已发出部分多出来的结尾，对不上就整个丢掉。
     */
    fun snapshot(full: String): String {
        if (emitted.isEmpty()) {
            emitted.append(full)
            return full
        }
        repeated = true
        if (!full.startsWith(emitted)) return ""
        val rest = full.substring(emitted.length)
        emitted.append(rest)
        return rest
    }

    companion object {
        const val MIN_REPEAT_CHARS = 20
    }
}
