package org.xmyun.starmate.feature.mobileuse.tree

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.xmyun.starmate.feature.mobileuse.model.NodeBounds
import org.xmyun.starmate.feature.mobileuse.model.UiNode

/**
 * 把 AccessibilityNodeInfo 树转成 [UiNode] 列表（深度优先），同时记下编号到原节点的对应，供执行动作用。
 * 只收可见节点；深度和数量都有上限，防止异常的界面树拖垮服务。
 */
object NodeMapper {
    const val MAX_DEPTH = 40
    const val MAX_NODES = 800
    private const val MAX_DESC_TEXT = 120

    class Mapped(val nodes: List<UiNode>, val handles: Map<Int, AccessibilityNodeInfo>)

    fun map(root: AccessibilityNodeInfo): Mapped {
        val nodes = ArrayList<UiNode>()
        val handles = HashMap<Int, AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (nodes.size >= MAX_NODES || depth > MAX_DEPTH) return
            if (node.isVisibleToUser) {
                val id = nodes.size
                nodes += toUiNode(node, id)
                handles[id] = node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1)
            }
        }
        visit(root, 0)
        return Mapped(nodes, handles)
    }

    fun toUiNode(node: AccessibilityNodeInfo, id: Int): UiNode {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val clickable = node.isClickable
        val descendant = if ((clickable || node.isLongClickable) && text.isNullOrBlank() && desc.isNullOrBlank()) {
            collectText(node, depth = 0, out = StringBuilder()).toString().trim().ifBlank { null }
        } else {
            null
        }
        return UiNode(
            id = id,
            className = node.className?.toString(),
            text = text,
            contentDescription = desc,
            hint = node.hintText?.toString(),
            viewId = node.viewIdResourceName,
            clickable = clickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            isPassword = node.isPassword,
            focused = node.isFocused,
            packageName = node.packageName?.toString(),
            bounds = NodeBounds(rect.left, rect.top, rect.right, rect.bottom),
            descendantText = descendant,
        )
    }

    private fun collectText(node: AccessibilityNodeInfo, depth: Int, out: StringBuilder): StringBuilder {
        if (depth > 4 || out.length >= MAX_DESC_TEXT) return out
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!child.isVisibleToUser) continue
            val t = child.text?.toString()?.takeIf { it.isNotBlank() } ?: child.contentDescription?.toString()
            if (!t.isNullOrBlank()) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(if (child.isPassword) UiTreeSerializer.PASSWORD_MASK else t)
            }
            collectText(child, depth + 1, out)
        }
        return out
    }
}
