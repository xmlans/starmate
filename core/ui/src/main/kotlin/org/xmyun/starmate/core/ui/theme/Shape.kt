package org.xmyun.starmate.core.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 星伴的圆角尺寸。整体以大圆角为主，悬浮的条和按钮是整条圆头的胶囊。 */
object StarmateRadius {
    /** 小组件：输入框、标签、图标底、菜单。 */
    val Small = 14.dp

    /** 卡片、设置分组。 */
    val Card = 24.dp

    /** 聊天气泡。 */
    val Bubble = 20.dp

    /** 气泡靠近说话人一侧下角的小圆角，形成“尾巴”的感觉。 */
    val BubbleTail = 6.dp

    /** 对话框、底部弹层。 */
    val Sheet = 32.dp
}

/**
 * Material 组件按形状档位取圆角：输入框用 extraSmall，标签和菜单用 small，
 * Card 用 medium，FAB 用 large，对话框和底部弹层用 extraLarge。
 */
val StarmateShapes = Shapes(
    extraSmall = RoundedCornerShape(StarmateRadius.Small),
    small = RoundedCornerShape(StarmateRadius.Small),
    medium = RoundedCornerShape(StarmateRadius.Card),
    large = RoundedCornerShape(StarmateRadius.Card),
    extraLarge = RoundedCornerShape(StarmateRadius.Sheet),
)

/** 卡片形状，24dp。等同于 MaterialTheme.shapes.medium。 */
val CardShape = RoundedCornerShape(StarmateRadius.Card)

/** 胶囊：两端是整圆。悬浮的标签栏、输入框、时间标签都用它。 */
val PillShape = CircleShape

/** 聊天气泡形状，四角 20dp。 */
val ChatBubbleShape = RoundedCornerShape(StarmateRadius.Bubble)

/** 用户发出的气泡（靠右）：右下角收小。会随 RTL 布局自动镜像。 */
val OutgoingBubbleShape = RoundedCornerShape(
    topStart = StarmateRadius.Bubble,
    topEnd = StarmateRadius.Bubble,
    bottomEnd = StarmateRadius.BubbleTail,
    bottomStart = StarmateRadius.Bubble,
)

/** 她发来的气泡（靠左）：左下角收小。 */
val IncomingBubbleShape = RoundedCornerShape(
    topStart = StarmateRadius.Bubble,
    topEnd = StarmateRadius.Bubble,
    bottomEnd = StarmateRadius.Bubble,
    bottomStart = StarmateRadius.BubbleTail,
)
