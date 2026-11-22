package org.xmyun.starmate.feature.mobileuse.tree

import org.xmyun.starmate.feature.mobileuse.model.UiNode

/**
 * 判断两份快照里同一序号的节点是不是“同一个元素”。
 * 模型看到第一份快照选了序号，执行前重新取屏幕；对不上就不执行，让模型重新看。
 */
object NodeIdentity {
    fun of(node: UiNode): String = listOf(
        node.className,
        node.viewId,
        node.text,
        node.contentDescription,
        node.descendantText,
        node.packageName,
        node.bounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" },
        node.clickable, node.longClickable, node.editable, node.scrollable, node.isPassword,
    ).joinToString("\u0001") { it?.toString() ?: "" }

    fun same(a: UiNode?, b: UiNode?): Boolean = a != null && b != null && of(a) == of(b)
}
