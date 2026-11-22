package org.xmyun.starmate.core.engine

/** 粗略估算 token：CJK 字符每个算 1，其余字符每 4 个算 1，向上取整。 */
object TokenEstimator {

    fun estimate(text: String): Int {
        var cjk = 0
        var other = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isCjk(cp)) cjk++ else other++
            i += Character.charCount(cp)
        }
        return cjk + (other + 3) / 4
    }

    private fun isCjk(codePoint: Int): Boolean {
        when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.BOPOMOFO -> return true
            else -> Unit
        }
        // 全角标点和 CJK 符号也按 1 计
        val block = Character.UnicodeBlock.of(codePoint)
        return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }
}
