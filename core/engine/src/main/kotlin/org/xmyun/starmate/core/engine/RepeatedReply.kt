package org.xmyun.starmate.core.engine

/**
 * 回复整段重复了两遍时收成一份。不论是服务端重发还是模型自己写重了，都不该原样存下：
 * 重复的消息进了历史之后，模型会跟着模仿，后面的回复也一条接一条地重复。
 *
 * 只认“同一段文字 + 至多几个空白 + 同一段文字”，而且那段文字去掉空白后至少 [MIN_CHARS] 个字符，
 * “晚安晚安”这种短的叠词不受影响。
 */
internal object RepeatedReply {

    const val MIN_CHARS = 20
    private const val MAX_GAP = 4

    /** 是整段重复时返回其中一份，否则返回 null。 */
    fun collapse(text: CharSequence): String? {
        val trimmed = text.toString().trim()
        for (gap in 0..MAX_GAP) {
            val rest = trimmed.length - gap
            if (rest <= 0 || rest % 2 != 0) continue
            val half = rest / 2
            val first = trimmed.substring(0, half)
            if (first.trim().length < MIN_CHARS) continue
            if (trimmed.substring(half, half + gap).isNotBlank()) continue
            if (trimmed.substring(half + gap) == first) return first.trimEnd()
        }
        return null
    }
}
