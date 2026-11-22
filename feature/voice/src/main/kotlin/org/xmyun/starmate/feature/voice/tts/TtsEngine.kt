package org.xmyun.starmate.feature.voice.tts

import java.io.File

sealed interface TtsResult {
    data object Success : TtsResult

    /** reason 只写给诊断日志，不含 key 和请求内容。 */
    data class Failure(val reason: String) : TtsResult
}

/** 一个语音合成引擎。实现方保证：返回 Failure 时 target 不存在（不留下空文件或半截文件）。 */
interface TtsEngine {
    /** 记账用的模型名。 */
    val model: String

    /** 输出文件的扩展名，例如 mp3、wav。 */
    val format: String

    suspend fun synthesize(text: String, tone: String?, target: File): TtsResult
}

/** 送进合成引擎前的清理：去掉表情符号和 Markdown 记号，空白折叠。显示用的文字稿不受影响。 */
object TtsText {
    private val MARKDOWN = Regex("[*_`#~>|]+")
    private val WHITESPACE = Regex("\\s+")

    fun clean(transcript: String): String {
        val sb = StringBuilder(transcript.length)
        var i = 0
        while (i < transcript.length) {
            val cp = transcript.codePointAt(i)
            if (!isEmojiLike(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString().replace(MARKDOWN, " ").replace(WHITESPACE, " ").trim()
    }

    private fun isEmojiLike(cp: Int): Boolean {
        if (cp == 0x200D || cp in 0xFE00..0xFE0F || cp == 0x20E3) return true // 零宽连接符、变体选择符、键帽
        if (cp in 0x1F000..0x1FAFF) return true // 各类表情和符号
        if (cp in 0x1F3FB..0x1F3FF) return true // 肤色
        return Character.getType(cp) == Character.OTHER_SYMBOL.toInt() && cp >= 0x2190
    }
}
