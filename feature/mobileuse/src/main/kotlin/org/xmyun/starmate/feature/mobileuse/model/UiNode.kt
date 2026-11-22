package org.xmyun.starmate.feature.mobileuse.model

/** 屏幕坐标里的矩形。 */
data class NodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * 界面树里的一个节点，已从 AccessibilityNodeInfo 转成纯数据。
 *
 * @param id 在这一份快照里的原始编号（深度优先顺序），不是发给模型的序号。
 * @param descendantText 可点击但自己没有文字的容器，取子孙节点文字拼起来，用作它的标签。
 */
data class UiNode(
    val id: Int,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val viewId: String? = null,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val isPassword: Boolean = false,
    val focused: Boolean = false,
    val packageName: String? = null,
    val bounds: NodeBounds? = null,
    val descendantText: String? = null,
) {
    val interactive: Boolean get() = clickable || longClickable || editable || scrollable

    val hasText: Boolean
        get() = !text.isNullOrBlank() || !contentDescription.isNullOrBlank() || !hint.isNullOrBlank()

    /** 守卫匹配关键字时看的全部文字。 */
    fun allTexts(): List<String> = listOfNotNull(text, contentDescription, hint, descendantText).filter { it.isNotBlank() }
}
