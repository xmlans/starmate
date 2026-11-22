package org.xmyun.starmate.feature.voice.tag

/** 一段语音：要说出口的话和可选的语气。 */
data class VoiceSegment(val transcript: String, val tone: String?)

/** 解析后的回复，按原文顺序排列的文字段和语音段。 */
sealed interface VoicePart {
    data class Text(val text: String) : VoicePart
    data class Voice(val segment: VoiceSegment) : VoicePart

    /** 去掉了一个没有内容的标签（空语音段、孤立闭标签等），只影响两侧文字的拼接。 */
    data object Gap : VoicePart
}

/**
 * @param text 去掉全部语音段后的显示文字。
 * @param changed 原文里有没有任何语音标签（包括孤立的闭标签、被截断的标签）。为 false 时 text 与原文逐字相同。
 */
data class ParsedVoiceReply(
    val text: String,
    val segments: List<VoiceSegment>,
    val parts: List<VoicePart>,
    val changed: Boolean,
)

/**
 * `<voice>…</voice>` / `<voice tone="…">…</voice>` 标记的解析。纯 Kotlin，任何输入都不抛异常。
 *
 * 规则：
 * - 标签名不分大小写；属性值可用 " ' “” ‘’ 「」 引起来，也可不加引号；未知属性忽略。
 * - 不是语音标签的 `<` 原样保留（例如“1 < 2”、`<video>`）。
 * - 孤立的 `</voice>` 和自闭合的 `<voice/>` 直接去掉。
 * - preview：完整语音段去掉；有开标签未闭合时从开标签起全部隐藏；末尾是可能属于标签的不完整前缀时也隐藏。
 * - parse：没闭合的开标签按闭合到末尾处理。
 */
object VoiceTagParser {

    private const val NAME = "voice"

    /** 一个标签（含属性）最长的字符数，超过就不当作标签。 */
    private const val MAX_TAG_LENGTH = 256

    private val TONE_ATTRIBUTES = setOf("tone", "语气")

    // ---- 对外 ----

    fun preview(raw: String): String {
        if (raw.indexOf('<') < 0) return raw
        return try {
            previewInternal(raw)
        } catch (e: RuntimeException) {
            // 理论上不会走到这里；万一有漏掉的情况，宁可少显示也不把标签露出来
            raw.substring(0, raw.indexOf('<')).trimEnd()
        }
    }

    fun parse(raw: String): ParsedVoiceReply {
        val unchanged = ParsedVoiceReply(raw, emptyList(), listOf(VoicePart.Text(raw)), changed = false)
        if (raw.indexOf('<') < 0) return unchanged
        return try {
            parseInternal(raw) ?: unchanged
        } catch (e: RuntimeException) {
            unchanged
        }
    }

    /**
     * 把 parts 拼成显示文字。restore(i) 为 true 的第 i 段语音还原成普通文字放回原位，其余语音段去掉。
     */
    fun render(parts: List<VoicePart>, restore: (Int) -> Boolean = { false }): String {
        val joiner = Joiner()
        var voiceIndex = 0
        for (part in parts) {
            when (part) {
                is VoicePart.Text -> joiner.text(part.text)
                is VoicePart.Voice -> {
                    if (restore(voiceIndex)) joiner.insert(part.segment.transcript) else joiner.gap()
                    voiceIndex++
                }
                VoicePart.Gap -> joiner.gap()
            }
        }
        return tidy(joiner.result())
    }

    // ---- 实现 ----

    private fun previewInternal(raw: String): String {
        val joiner = Joiner()
        var i = 0
        while (i < raw.length) {
            val lt = raw.indexOf('<', i)
            if (lt < 0) {
                joiner.text(raw.substring(i))
                break
            }
            joiner.text(raw.substring(i, lt))
            when (val match = matchTag(raw, lt)) {
                is TagMatch.Open -> {
                    joiner.gap()
                    if (match.selfClosing) {
                        i = match.end
                    } else {
                        when (val close = findClose(raw, match.end)) {
                            is CloseSearch.Found -> i = close.end
                            // 还没闭合：从开标签起全部隐藏
                            else -> return tidy(joiner.result())
                        }
                    }
                }
                is TagMatch.Close -> {
                    joiner.gap()
                    i = match.end
                }
                is TagMatch.Partial -> return tidy(joiner.result())
                TagMatch.None -> {
                    joiner.text("<")
                    i = lt + 1
                }
            }
        }
        return if (joiner.changed) tidy(joiner.result()) else raw
    }

    private fun parseInternal(raw: String): ParsedVoiceReply? {
        val parts = mutableListOf<VoicePart>()
        val buffer = StringBuilder()
        var changed = false

        fun flush() {
            if (buffer.isNotEmpty()) {
                parts += VoicePart.Text(buffer.toString())
                buffer.setLength(0)
            }
        }

        fun addVoice(rawTranscript: String, tone: String?) {
            flush()
            val transcript = cleanTranscript(rawTranscript)
            parts += if (transcript.isNotEmpty()) VoicePart.Voice(VoiceSegment(transcript, tone)) else VoicePart.Gap
        }

        fun addGap() {
            flush()
            parts += VoicePart.Gap
        }

        var i = 0
        while (i < raw.length) {
            val lt = raw.indexOf('<', i)
            if (lt < 0) {
                buffer.append(raw, i, raw.length)
                break
            }
            buffer.append(raw, i, lt)
            when (val match = matchTag(raw, lt)) {
                is TagMatch.Open -> {
                    changed = true
                    if (match.selfClosing) {
                        addVoice("", match.tone)
                        i = match.end
                    } else {
                        when (val close = findClose(raw, match.end)) {
                            is CloseSearch.Found -> {
                                addVoice(raw.substring(match.end, close.start), match.tone)
                                i = close.end
                            }
                            is CloseSearch.PartialAt -> {
                                addVoice(raw.substring(match.end, close.start), match.tone)
                                i = raw.length
                            }
                            CloseSearch.NotFound -> {
                                addVoice(raw.substring(match.end), match.tone)
                                i = raw.length
                            }
                        }
                    }
                }
                is TagMatch.Close -> {
                    changed = true
                    addGap()
                    i = match.end
                }
                is TagMatch.Partial -> {
                    if (match.nameComplete) {
                        // 末尾是写到一半的语音标签（例如回复被截断），没有内容可言，去掉
                        changed = true
                        addGap()
                    } else {
                        buffer.append(raw, lt, raw.length)
                    }
                    i = raw.length
                }
                TagMatch.None -> {
                    buffer.append('<')
                    i = lt + 1
                }
            }
        }
        if (!changed) return null
        flush()

        return ParsedVoiceReply(
            text = render(parts) { false },
            segments = parts.filterIsInstance<VoicePart.Voice>().map { it.segment },
            parts = parts,
            changed = true,
        )
    }

    /** 语音段内部：去掉嵌套的标签，空白折叠成一个空格。 */
    private fun cleanTranscript(text: String): String {
        if (text.isEmpty()) return ""
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val lt = text.indexOf('<', i)
            if (lt < 0) {
                out.append(text, i, text.length)
                break
            }
            out.append(text, i, lt)
            when (val match = matchTag(text, lt)) {
                is TagMatch.Open -> i = match.end
                is TagMatch.Close -> i = match.end
                is TagMatch.Partial -> i = if (match.nameComplete) text.length else {
                    out.append(text, lt, text.length)
                    text.length
                }
                TagMatch.None -> {
                    out.append('<')
                    i = lt + 1
                }
            }
        }
        return out.toString().trim().replace(WHITESPACE_RUN, " ")
    }

    private val WHITESPACE_RUN = Regex("\\s+")

    // ---- 标签匹配 ----

    private sealed interface TagMatch {
        data class Open(val end: Int, val tone: String?, val selfClosing: Boolean) : TagMatch
        data class Close(val end: Int) : TagMatch

        /** 文本在标签中途结束。nameComplete：已经看到完整的 `<voice` 或 `</voice`。 */
        data class Partial(val nameComplete: Boolean) : TagMatch
        data object None : TagMatch
    }

    private sealed interface CloseSearch {
        data class Found(val start: Int, val end: Int) : CloseSearch
        data class PartialAt(val start: Int) : CloseSearch
        data object NotFound : CloseSearch
    }

    private fun findClose(s: String, from: Int): CloseSearch {
        var k = s.indexOf('<', from)
        while (k >= 0) {
            when (val match = matchTag(s, k)) {
                is TagMatch.Close -> return CloseSearch.Found(k, match.end)
                is TagMatch.Partial -> return CloseSearch.PartialAt(k)
                else -> k = s.indexOf('<', k + 1)
            }
        }
        return CloseSearch.NotFound
    }

    /** s[p] 必须是 '<'。 */
    private fun matchTag(s: String, p: Int): TagMatch {
        var i = p + 1
        if (i >= s.length) return TagMatch.Partial(nameComplete = false)
        val closing = s[i] == '/'
        if (closing) i++
        for (expected in NAME) {
            if (i >= s.length) return TagMatch.Partial(nameComplete = false)
            if (s[i].lowercaseChar() != expected) return TagMatch.None
            i++
        }
        if (i >= s.length) return TagMatch.Partial(nameComplete = true)

        if (closing) {
            while (i < s.length && isHorizontalSpace(s[i]) && i - p <= MAX_TAG_LENGTH) i++
            if (i >= s.length) return TagMatch.Partial(nameComplete = true)
            return if (s[i] == '>') TagMatch.Close(i + 1) else TagMatch.None
        }

        val c = s[i]
        return when {
            c == '>' -> TagMatch.Open(i + 1, tone = null, selfClosing = false)
            c == '/' -> when {
                i + 1 >= s.length -> TagMatch.Partial(nameComplete = true)
                s[i + 1] == '>' -> TagMatch.Open(i + 2, tone = null, selfClosing = true)
                else -> TagMatch.None
            }
            c.isWhitespace() -> matchAttributes(s, p, i)
            else -> TagMatch.None
        }
    }

    /** 从 i（开标签名之后的空白）开始读属性，直到 '>'。 */
    private fun matchAttributes(s: String, p: Int, start: Int): TagMatch {
        var tone: String? = null
        var j = start
        while (true) {
            while (j < s.length && s[j].isWhitespace()) j++
            if (j - p > MAX_TAG_LENGTH) return TagMatch.None
            if (j >= s.length) return TagMatch.Partial(nameComplete = true)
            val c = s[j]
            if (c == '>') return TagMatch.Open(j + 1, tone, selfClosing = false)
            if (c == '/') {
                if (j + 1 >= s.length) return TagMatch.Partial(nameComplete = true)
                return if (s[j + 1] == '>') TagMatch.Open(j + 2, tone, selfClosing = true) else TagMatch.None
            }

            val nameStart = j
            while (j < s.length && isAttributeNameChar(s[j])) j++
            if (j >= s.length) return TagMatch.Partial(nameComplete = true)
            if (j == nameStart) return TagMatch.None
            val name = s.substring(nameStart, j)

            var k = j
            while (k < s.length && isHorizontalSpace(s[k])) k++
            if (k >= s.length) return TagMatch.Partial(nameComplete = true)
            if (s[k] != '=') {
                // 没有值的属性，忽略
                j = k
                continue
            }
            k++
            while (k < s.length && isHorizontalSpace(s[k])) k++
            if (k >= s.length) return TagMatch.Partial(nameComplete = true)

            val closers = closingQuotes(s[k])
            val value: String
            if (closers != null) {
                var e = k + 1
                while (true) {
                    if (e - p > MAX_TAG_LENGTH) return TagMatch.None
                    if (e >= s.length) return TagMatch.Partial(nameComplete = true)
                    val ch = s[e]
                    if (ch in closers) {
                        value = s.substring(k + 1, e)
                        j = e + 1
                        break
                    }
                    if (ch == '>') {
                        // 引号忘了闭合：宽容处理，标签在 '>' 处结束
                        val v = s.substring(k + 1, e)
                        if (name.lowercase() in TONE_ATTRIBUTES) tone = v.trim().ifEmpty { null } ?: tone
                        return TagMatch.Open(e + 1, tone, selfClosing = false)
                    }
                    if (ch == '\n' || ch == '<') return TagMatch.None
                    e++
                }
            } else {
                var e = k
                while (e < s.length && !s[e].isWhitespace() && s[e] != '>' && s[e] != '<') e++
                if (e >= s.length) return TagMatch.Partial(nameComplete = true)
                if (s[e] == '<') return TagMatch.None
                value = s.substring(k, e)
                j = e
            }
            if (name.lowercase() in TONE_ATTRIBUTES) tone = value.trim().ifEmpty { null } ?: tone
        }
    }

    private fun closingQuotes(open: Char): String? = when (open) {
        '"' -> "\""
        '\'' -> "'"
        '“' -> "”“\""
        '”' -> "”“\""
        '‘' -> "’‘'"
        '「' -> "」"
        '『' -> "』"
        else -> null
    }

    private fun isAttributeNameChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '-' || c == ':'

    private fun isHorizontalSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '　'

    // ---- 拼接与清理 ----

    /**
     * 把文字段拼起来。gap() 表示这里去掉了一个标签：两侧都是空格时只留一个。
     * insert() 放入还原的语音文字：两侧都是拉丁字母一类时补一个空格，中文直接相连。
     */
    private class Joiner {
        private val sb = StringBuilder()
        private var gapPending = false
        private var afterInsert = false

        /** 是否发生过去标签或插入。 */
        var changed = false
            private set

        fun gap() {
            gapPending = true
            changed = true
        }

        fun text(t: String) {
            if (t.isEmpty()) return
            var piece = t
            if (gapPending && (sb.isEmpty() || isHorizontalSpace(sb.last()))) {
                piece = piece.trimStart(' ', '\t', '　')
            }
            if (piece.isEmpty()) return
            if (afterInsert && sb.isNotEmpty() && needsSpace(sb.last(), piece.first())) sb.append(' ')
            sb.append(piece)
            gapPending = false
            afterInsert = false
        }

        fun insert(t: String) {
            changed = true
            if (t.isEmpty()) {
                gap()
                return
            }
            if (sb.isNotEmpty() && needsSpace(sb.last(), t.first())) sb.append(' ')
            sb.append(t)
            gapPending = false
            afterInsert = true
        }

        fun result(): String = sb.toString()

        private fun needsSpace(a: Char, b: Char): Boolean {
            if (a.isWhitespace() || b.isWhitespace()) return false
            val left = a.code < CJK_START && (a.isLetterOrDigit() || a in ".,!?;:")
            val right = b.code < CJK_START && b.isLetterOrDigit()
            return left && right
        }
    }

    private const val CJK_START = 0x2E80

    /** 去掉多余空行：只含空白的行视为空行，连续空行只留一个，首尾空白去掉。 */
    private fun tidy(s: String): String {
        if (s.isEmpty()) return s
        val lines = s.split('\n')
        val out = StringBuilder()
        var blankRun = false
        var started = false
        for (line in lines) {
            if (line.isBlank()) {
                if (started) blankRun = true
                continue
            }
            if (started) {
                out.append('\n')
                if (blankRun) out.append('\n')
            }
            out.append(line.trimEnd())
            started = true
            blankRun = false
        }
        return out.toString().trim()
    }
}
