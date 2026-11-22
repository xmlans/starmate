package org.xmyun.starmate.feature.memory.retrieval

/**
 * 把查询拆成两类：至少 3 个字符的词交给 FTS5 trigram 的 MATCH，
 * 少于 3 个字符的词（中文二字词、两个字母的英文）trigram 匹配不了，改用 LIKE。
 */
object KeywordSearch {
    private val ASCII = Regex("[a-z0-9_]{2,}")
    private val CJK = Regex("[㐀-鿿]+")
    const val MAX_LIKE_TERMS = 12
    const val MAX_MATCH_TERMS = 24

    data class Terms(val match: List<String>, val like: List<String>) {
        val isEmpty: Boolean get() = match.isEmpty() && like.isEmpty()
    }

    fun split(query: String?): Terms {
        val text = (query ?: "").lowercase()
        val match = LinkedHashSet<String>()
        val like = LinkedHashSet<String>()
        ASCII.findAll(text).forEach { m -> if (m.value.length >= 3) match += m.value else like += m.value }
        CJK.findAll(text).forEach { m ->
            val run = m.value
            when {
                run.length == 1 -> Unit
                else -> {
                    for (i in 0 until run.length - 1) like += run.substring(i, i + 2)
                    for (i in 0 until run.length - 2) match += run.substring(i, i + 3)
                }
            }
        }
        return Terms(sample(match.toList(), MAX_MATCH_TERMS), sample(like.toList(), MAX_LIKE_TERMS))
    }

    /** 词太多时均匀取样，控制查询开销。 */
    private fun sample(terms: List<String>, max: Int): List<String> =
        if (terms.size <= max) terms else List(max) { i -> terms[i * terms.size / max] }

    /** FTS5 查询：每个词加双引号当作短语，用 OR 连接。 */
    fun matchExpression(terms: List<String>): String =
        terms.joinToString(" OR ") { "\"" + it.replace("\"", "\"\"") + "\"" }

    /** LIKE 模式：转义 % _ 和转义符本身。 */
    fun likePattern(term: String): String =
        "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
