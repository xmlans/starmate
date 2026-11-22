package org.xmyun.starmate.feature.memory.retrieval

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.sqrt
import org.xmyun.starmate.feature.memory.model.Episode
import org.xmyun.starmate.feature.memory.model.Gist
import org.xmyun.starmate.feature.memory.model.Mem
import org.xmyun.starmate.feature.memory.model.Span
import org.xmyun.starmate.feature.memory.text.hasToken
import org.xmyun.starmate.feature.memory.text.orderedTokens
import org.xmyun.starmate.feature.memory.text.tokens

/** 词项相关度：score 为 0–1，hits 为命中的词数。 */
data class Lex(val score: Double, val hits: Int)

/** 被归档的记忆要在话题上足够相关才会被想起。 */
const val RECALL_MIN_RELEVANCE = 0.45
const val RECALL_MIN_MATCHES = 2

/** 新增时与已有记忆相似度超过此值视为重复。 */
const val DUPLICATE_SIMILARITY = 0.8

/** 联想：被想起的记忆把一部分相关度传给有关联的记忆。 */
const val SPREAD_FACTOR = 0.6
const val SPREAD_MIN_PARENT = 0.45
const val ASSOC_MIN = 0.3
const val AUTO_LINK_SIMILARITY = 0.3
const val AUTO_LINKS = 2
private const val MAX_QUERY_TERMS = 64

private val RECALL_CUES = Regex(
    "以前|之前|上次|那次|那天|那时|记得|还记得|当时|曾经|说过|提过|聊过|告诉过你|跟你讲过|忘了|" +
        "remember|last time|before|used to|told you|mentioned|back then",
    RegexOption.IGNORE_CASE,
)

/** 用户提到过去时才去翻原话，避免无关的旧对话干扰。 */
fun wantsEpisodes(query: String?, span: Span?): Boolean =
    span != null || RECALL_CUES.containsMatchIn(query ?: "")

/** IDF 加权的词项匹配：罕见词（人名、爱好）比常见词更能说明相关。 */
class Lexical(private val docs: Map<Long, Set<String>>) {
    private val idf: Map<String, Double>

    init {
        val df = HashMap<String, Int>()
        for (toks in docs.values) for (t in toks) df[t] = (df[t] ?: 0) + 1
        val n = docs.size
        idf = df.mapValues { (_, c) -> ln(1 + (n - c + 0.5) / (c + 0.5)) }
    }

    /** 当前消息权重 1，最近对话权重 0.5；只保留库里出现过的词。 */
    fun query(primary: String, context: String = ""): Map<String, Double> {
        val weights = LinkedHashMap<String, Double>()
        for (t in tokens(context)) weights[t] = 0.5
        for (t in tokens(primary)) weights[t] = 1.0
        return weights.filterKeys { it in idf }.mapValues { (t, w) -> w * idf.getValue(t) }
    }

    /** 查询里只有一个有效词时，命中它就算命中充分（按 2 个计），否则短查询永远想不起东西。 */
    fun scores(q: Map<String, Double>): Map<Long, Lex> {
        val total = q.values.sum()
        if (total == 0.0) return emptyMap()
        val single = q.size == 1
        val result = LinkedHashMap<Long, Lex>()
        for ((id, doc) in docs) {
            val hit = q.keys.filter { it in doc }
            if (hit.isNotEmpty()) result[id] = Lex(hit.sumOf { q.getValue(it) } / total, if (single) 2 else hit.size)
        }
        return result
    }

    companion object {
        fun ofMemories(memories: Iterable<Mem>) = Lexical(memories.associate { it.id to tokens(it.text) })
        fun ofEpisodes(episodes: Iterable<Episode>) = Lexical(
            episodes.associate { e -> e.id to (e.keys.split(' ').filter { it.isNotEmpty() }.toSet().ifEmpty { tokens(e.text) }) },
        )
        fun ofGists(gists: Iterable<Gist>) = Lexical(
            gists.associate { g -> g.id to (g.keys.split(' ').filter { it.isNotEmpty() }.toSet().ifEmpty { tokens(g.summary) }) },
        )
    }
}

/** 与 [Lexical] 相同的 IDF 打分，用于大量原话：只统计查询里的词。docs 是 {编号: 空格分隔的词项串}。 */
fun keysScores(docs: Map<Long, String>, query: String, corpusSize: Int = docs.size): Map<Long, Lex> {
    var terms = orderedTokens(query)
    if (terms.size > MAX_QUERY_TERMS) terms = List(MAX_QUERY_TERMS) { i -> terms[i * terms.size / MAX_QUERY_TERMS] }
    if (terms.isEmpty() || docs.isEmpty()) return emptyMap()
    val hits = docs.mapValues { (_, keys) -> terms.filter { hasToken(keys, it) } }
    val df = HashMap<String, Int>()
    for (found in hits.values) for (t in found) df[t] = (df[t] ?: 0) + 1
    // 候选只是全部原话里有命中的那部分，IDF 按整个检索范围的篇数算，和参考实现一致
    val n = maxOf(corpusSize, docs.size)
    val idf = df.mapValues { (_, c) -> ln(1 + (n - c + 0.5) / (c + 0.5)) }
    val total = idf.values.sum()
    if (total == 0.0) return emptyMap()
    val single = idf.size == 1
    return hits.filterValues { it.isNotEmpty() }
        .mapValues { (_, found) -> Lex(found.sumOf { idf.getValue(it) } / total, if (single) 2 else found.size) }
}

object Vectors {
    fun pack(vec: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vec) buffer.putFloat(v)
        return buffer.array()
    }

    fun unpack(blob: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(blob.size / 4) { buffer.getFloat() }
    }

    /** 暴力余弦；维度不同的向量被忽略。 */
    fun cosineMany(query: FloatArray, vectors: Map<Long, FloatArray>): Map<Long, Double> {
        if (query.isEmpty() || vectors.isEmpty()) return emptyMap()
        var qq = 0.0
        for (x in query) qq += x * x
        val qn = sqrt(qq).takeIf { it > 0 } ?: 1.0
        val result = LinkedHashMap<Long, Double>()
        for ((id, v) in vectors) {
            if (v.size != query.size) continue
            var dot = 0.0
            var vv = 0.0
            for (i in v.indices) {
                dot += v[i] * query[i]
                vv += v[i] * v[i]
            }
            val vn = sqrt(vv).takeIf { it > 0 } ?: 1.0
            result[id] = dot / (qn * vn)
        }
        return result
    }
}

/** 词项 0.4 + 向量 0.6；没有向量时只用词项。向量相似度映射到 0–1（0.25 以下视为无关）。 */
fun hybrid(lex: Map<Long, Lex>, dense: Map<Long, Double>?, ids: Iterable<Long>): Map<Long, Double> {
    val result = LinkedHashMap<Long, Double>()
    for (i in ids) {
        val lexical = lex[i]?.score ?: 0.0
        val cos = dense?.get(i)
        result[i] = if (cos == null) {
            lexical
        } else {
            val semantic = ((cos - 0.25) / 0.5).coerceIn(0.0, 1.0)
            0.4 * lexical + 0.6 * semantic
        }
    }
    return result
}

/** 联想：想起一条，顺带想起和它有关联的。只扩散一跳。返回 (扩散后的相关度, 主要靠联想才相关的编号)。 */
fun spread(rel: Map<Long, Double>, links: Map<Long, List<Pair<Long, Double>>>?): Pair<Map<Long, Double>, Set<Long>> {
    if (links.isNullOrEmpty()) return rel to emptySet()
    val result = LinkedHashMap(rel)
    val assoc = HashSet<Long>()
    for ((parent, score) in rel) {
        if (score < SPREAD_MIN_PARENT) continue
        for ((child, weight) in links[parent].orEmpty()) {
            val boost = SPREAD_FACTOR * weight * score
            val base = rel[child] ?: 0.0
            if (boost > (result[child] ?: 0.0) - base) {
                result[child] = base + boost
                if (boost >= ASSOC_MIN && base < SPREAD_MIN_PARENT) assoc += child
            }
        }
    }
    return result to assoc
}

/** 归档记忆 / 原话要足够相关才会被想起：词项命中够多，或语义上非常接近。 */
fun recallable(rel: Double, lexHits: Int, cos: Double?): Boolean {
    if (rel < RECALL_MIN_RELEVANCE) return false
    return lexHits >= RECALL_MIN_MATCHES || (cos != null && cos >= 0.6)
}

/** 一组记录对某段文字的混合相关度。 */
class Relevance(
    val rel: Map<Long, Double>,
    val lex: Map<Long, Lex>,
    val cos: Map<Long, Double>?,
) {
    companion object {
        val EMPTY = Relevance(emptyMap(), emptyMap(), null)
    }
}
