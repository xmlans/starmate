package org.xmyun.starmate.core.ui.glass

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.xmyun.starmate.core.ui.theme.glassBorder
import org.xmyun.starmate.core.ui.theme.glassFallback
import org.xmyun.starmate.core.ui.theme.glassTint
import org.xmyun.starmate.core.ui.theme.isLight
import org.xmyun.starmate.core.ui.theme.softShadow

/**
 * 毛玻璃的用法分两步：
 *
 * 1. 会被盖住的内容（列表、壁纸）加 [glassSource]，它负责把内容录下来；
 * 2. 盖在上面的条（标题栏、输入框、底部标签栏）加 [glass]，它把下面录到的内容模糊后画出来。
 *
 * 两者用同一个 [GlassState] 连起来。加了 [glass] 的元素不能放在同一个 state 的 [glassSource] 里面。
 *
 * Android 12 及以上是真模糊；Android 11 及以下系统不支持，自动改成一层几乎不透明的底色，文字照样看得清。
 */
@Stable
class GlassState internal constructor(internal val haze: HazeState)

@Composable
fun rememberGlassState(): GlassState {
    val haze = rememberHazeState()
    return remember(haze) { GlassState(haze) }
}

/** 标记“这块内容会被毛玻璃盖住”。 */
fun Modifier.glassSource(state: GlassState): Modifier = hazeSource(state.haze)

/**
 * 把这个元素的背景变成毛玻璃，并按 [shape] 裁剪。
 *
 * @param outlined 描一圈高光边。悬浮的胶囊和卡片要，贴边的整条栏不要。
 * @param elevation 悬浮高度，决定投影的大小（见 [softShadow]）。贴边的整条栏传 0。
 */
@Composable
fun Modifier.glass(
    state: GlassState,
    shape: Shape = RectangleShape,
    outlined: Boolean = shape != RectangleShape,
    elevation: Dp = 0.dp,
): Modifier {
    val colors = MaterialTheme.colorScheme
    val tint = colors.glassTint
    val fallback = colors.glassFallback
    val base = colors.surface
    // 自己拼的样式按对象身份比较，所以要记住，否则每次重组都会让模糊重新配置一遍。
    val style = remember(tint, fallback, base) {
        HazeBlurStyle {
            blurRadius(GlassBlurRadius)
            noiseFactor(0.03f)
            backgroundColor(base)
            colorEffects(listOf(HazeColorEffect.tint(tint)))
            fallbackColorEffect(HazeColorEffect.tint(fallback))
        }
    }
    val border = if (outlined) Modifier.border(GlassBorderWidth, colors.glassBorder, shape) else Modifier
    return this
        .softShadow(shape, elevation)
        .clip(shape)
        .hazeBlur(input = HazeInput.Sources(state.haze), style = style)
        .then(border)
}

/**
 * 悬浮元素的投影：一团很淡、很散、带一点主题色的影子，稍微往下偏。
 *
 * 不用系统的 elevation 投影：系统投影边缘偏实，压在粉彩壁纸上发灰发脏。
 * 影子画在元素自己的范围之外，所以元素本身要不透明或者是毛玻璃，否则会透出一块深色。
 *
 * @param elevation 悬浮高度，影子的模糊半径和下移距离都按它算。0 时不画。
 */
@Composable
fun Modifier.softShadow(shape: Shape, elevation: Dp): Modifier {
    if (elevation <= 0.dp) return this
    val colors = MaterialTheme.colorScheme
    val color = colors.softShadow.copy(alpha = if (colors.isLight) 0.14f else 0.45f)
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val paint = Paint().apply { this.color = color }
        paint.asFrameworkPaint().maskFilter = BlurMaskFilter((elevation * 2f).toPx(), BlurMaskFilter.Blur.NORMAL)
        val offset = (elevation * 0.5f).toPx()
        onDrawBehind {
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.translate(0f, offset)
                canvas.drawOutline(outline, paint)
                canvas.restore()
            }
        }
    }
}

/**
 * 底部悬浮标签栏占掉的高度（不含系统导航条）。带标签栏的页面要把它加到内容的底部留白上，
 * 这样列表最后一项能滚到标签栏上方。没有标签栏时是 0。由 :app 的主框架提供。
 */
val LocalBottomBarInset = compositionLocalOf { 0.dp }

private val GlassBlurRadius = 24.dp
private val GlassBorderWidth = 0.8.dp
