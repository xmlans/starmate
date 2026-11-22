package org.xmyun.starmate.feature.memory.text

import java.security.MessageDigest

private val ASCII_WORD = Regex("[a-z0-9_]{2,}")
private val CJK_RUN = Regex("[㐀-鿿]+")
private val ORDERED = Regex("[a-z0-9_]{2,}|[㐀-鿿]+")
private val CJK_CHAR = Regex("[぀-ヿ㐀-鿿가-힯]")
private val SPACES = Regex("\\s+")

/** 中文按字二元组，其他按单词，足够做轻量相关度。 */
fun tokens(text: String?): Set<String> {
    val lower = (text ?: "").lowercase()
    val result = HashSet<String>()
    ASCII_WORD.findAll(lower).forEach { result += it.value }
    CJK_RUN.findAll(lower).forEach { match ->
        val run = match.value
        if (run.length == 1) result += run
        for (i in 0 until run.length - 1) result += run.substring(i, i + 2)
    }
    return result
}

/** 与 [tokens] 相同的切分，但保留出现顺序，去重。 */
fun orderedTokens(text: String?): List<String> {
    val result = LinkedHashSet<String>()
    ORDERED.findAll((text ?: "").lowercase()).forEach { match ->
        val run = match.value
        when {
            run[0].code < 128 -> result += run
            run.length == 1 -> result += run
            else -> for (i in 0 until run.length - 1) result += run.substring(i, i + 2)
        }
    }
    return result.toList()
}

/** 粗估 token：中日韩字符约 1 字 1 token，其他约 4 字符 1 token。 */
fun estimateTokens(text: String?): Int {
    val value = text ?: ""
    val cjk = CJK_CHAR.findAll(value).count()
    return cjk + (value.length - cjk + 3) / 4
}

fun similarity(a: String, b: String): Double {
    val ta = tokens(a)
    val tb = tokens(b)
    if (ta.isEmpty() || tb.isEmpty()) return if (a.trim() == b.trim()) 1.0 else 0.0
    return ta.intersect(tb).size.toDouble() / minOf(ta.size, tb.size)
}

const val MAX_CONTENT_CHARS = 150
const val MAX_TAGS_CHARS = 60

fun cleanContent(text: String?, limit: Int = MAX_CONTENT_CHARS): String =
    SPACES.replace(text ?: "", " ").trim().take(limit)

fun clampImportance(value: Int?, default: Int = 3): Int = (value ?: default).coerceIn(1, 5)

fun clampEmotion(value: Int?): Int = (value ?: 0).coerceIn(0, 3)

fun joinKeys(keys: Iterable<String>): String = keys.toSortedSet().joinToString(" ")

/** 空格分隔的词项串里是否有完整的这个词。 */
fun hasToken(keys: String, term: String): Boolean {
    var start = keys.indexOf(term)
    while (start >= 0) {
        val end = start + term.length
        if ((start == 0 || keys[start - 1] == ' ') && (end == keys.length || keys[end] == ' ')) return true
        start = keys.indexOf(term, start + 1)
    }
    return false
}

/** 只用于展示或模型输入：取首尾摘要，数据库始终保留完整原文。 */
fun excerpt(text: String, limit: Int, omitted: String): String {
    if (text.length <= limit) return text
    val half = maxOf(1, (limit - 20) / 2)
    return text.take(half) + omitted + text.takeLast(half)
}

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
