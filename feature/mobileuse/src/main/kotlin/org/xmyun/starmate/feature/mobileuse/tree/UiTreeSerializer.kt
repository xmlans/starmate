package org.xmyun.starmate.feature.mobileuse.tree

import org.xmyun.starmate.feature.mobileuse.model.ScreenState
import org.xmyun.starmate.feature.mobileuse.model.UiNode

/**
 * 把界面树压成发给模型的带编号列表。
 * - 只保留可交互或带文字的节点，按树的顺序连续编号，同一份输入两次结果完全相同。
 * - 每段文字最多 80 字符；密码框的文字一律是 •••；最多 150 个节点。
 * - 屏幕文字里的换行和尖括号被替换，保证它们没法伪造结束标记（G9）。
 * 守卫按同一个 [visibleNodes] 把序号对应回节点，所以模型说的序号和守卫检查的节点是同一个。
 */
object UiTreeSerializer {
    const val MAX_NODES = 150
    const val MAX_TEXT = 80
    const val PASSWORD_MASK = "•••"
    const val BEGIN_MARKER = "<<<SCREEN_CONTENT_BEGIN>>>"
    const val END_MARKER = "<<<SCREEN_CONTENT_END>>>"

    /** 发给模型的节点，下标就是序号。 */
    fun visibleNodes(nodes: List<UiNode>): List<UiNode> = selectAll(nodes).take(MAX_NODES)

    private fun selectAll(nodes: List<UiNode>): List<UiNode> =
        nodes.filter { node ->
            val onScreen = node.bounds?.isEmpty != true
            onScreen && (node.interactive || node.hasText)
        }

    fun serialize(screen: ScreenState, chinese: Boolean): String {
        val all = selectAll(screen.nodes)
        val shown = all.take(MAX_NODES)
        val sb = StringBuilder()
        sb.append(BEGIN_MARKER).append('\n')
        val app = truncate(clean(screen.appLabel ?: "?"))
        val pkg = truncate(clean(screen.packageName ?: "?"))
        sb.append(if (chinese) "应用：" else "App: ").append(app).append(" (").append(pkg).append(")\n")
        shown.forEachIndexed { index, node -> sb.append(line(index, node)).append('\n') }
        if (all.size > shown.size) {
            val more = all.size - shown.size
            sb.append(
                if (chinese) "（还有更多元素未列出，共 $more 个）" else "(more elements not listed: $more)",
            ).append('\n')
        }
        sb.append(END_MARKER)
        return sb.toString()
    }

    private fun line(index: Int, node: UiNode): String {
        val sb = StringBuilder()
        sb.append('[').append(index).append("] ").append(shortClass(node.className))
        val label = if (node.isPassword) {
            PASSWORD_MASK
        } else {
            node.text?.takeIf { it.isNotBlank() } ?: node.descendantText?.takeIf { it.isNotBlank() }
        }
        if (label != null) sb.append(" \"").append(truncate(clean(label))).append('"')
        node.contentDescription?.takeIf { it.isNotBlank() }?.let {
            sb.append(" desc=\"").append(if (node.isPassword) PASSWORD_MASK else truncate(clean(it))).append('"')
        }
        if (node.text.isNullOrBlank()) {
            node.hint?.takeIf { it.isNotBlank() }?.let { sb.append(" hint=\"").append(truncate(clean(it))).append('"') }
        }
        val flags = buildList {
            if (node.clickable) add("tap")
            if (node.longClickable) add("long")
            if (node.editable) add("edit")
            if (node.scrollable) add("scroll")
            if (node.isPassword) add("password")
        }
        if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append('}')
        return sb.toString()
    }

    internal fun truncate(s: String): String = if (s.length <= MAX_TEXT) s else s.take(MAX_TEXT) + "…"

    /** 去掉换行、尖括号和引号，屏幕文字没法伪造分隔标记或打断这一行。 */
    internal fun clean(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '\n', '\r', '\t', ' ', ' ' -> append(' ')
                '<' -> append('‹')
                '>' -> append('›')
                '"' -> append('\'')
                else -> append(c)
            }
        }
    }.trim()

    // 类名也来自被操作的应用，同样要清洗
    private fun shortClass(className: String?): String =
        className?.substringAfterLast('.')?.let { truncate(clean(it)) }?.takeIf { it.isNotBlank() } ?: "View"
}
