package org.xmyun.starmate.core.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * 种子色派生用的色彩工具。
 *
 * 色相和彩度在 OKLCH 里取（色相比 HSL 均匀），明度用 Material 的“色调”（tone = CIELAB L*，0 黑 100 白）。
 * 色调直接决定相对亮度，所以 Material 标准色调配对（40/100、30/90、80/20 等）的对比度只取决于色调，
 * 不随种子色变化，任何种子都能满足 4.5:1。
 */
internal object ColorMath {

    /** 色调 → WCAG 相对亮度 Y。 */
    fun toneToY(tone: Double): Double {
        val fy = (tone + 16.0) / 116.0
        val ft = fy * fy * fy
        return if (ft > 216.0 / 24389.0) ft else tone / (24389.0 / 27.0)
    }

    /** sRGB 分量（0..1）→ 线性分量。 */
    fun linearize(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun delinearize(c: Double): Double =
        if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055

    /** 返回 (L, C, hue 弧度)。 */
    fun toOklch(color: Color): DoubleArray {
        val r = linearize(color.red.toDouble())
        val g = linearize(color.green.toDouble())
        val b = linearize(color.blue.toDouble())
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val bigL = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        return doubleArrayOf(bigL, hypot(a, bb), atan2(bb, a))
    }

    /** OKLab → 线性 sRGB（不裁剪，可能越界）。 */
    private fun oklabToLinear(bigL: Double, a: Double, b: Double, out: DoubleArray) {
        val l = (bigL + 0.3963377774 * a + 0.2158037573 * b).let { it * it * it }
        val m = (bigL - 0.1055613458 * a - 0.0638541728 * b).let { it * it * it }
        val s = (bigL - 0.0894841775 * a - 1.2914855480 * b).let { it * it * it }
        out[0] = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        out[1] = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        out[2] = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
    }

    private fun luminance(rgb: DoubleArray) = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]

    private fun inGamut(rgb: DoubleArray) = rgb.all { it >= -1e-6 && it <= 1.0 + 1e-6 }

    /** 固定色相和彩度，二分 OKLab 明度使相对亮度等于 targetY。 */
    private fun solveLightness(hue: Double, chroma: Double, targetY: Double, rgb: DoubleArray): Double {
        val a = chroma * cos(hue)
        val b = chroma * sin(hue)
        var lo = 0.0
        var hi = 1.0
        repeat(40) {
            val mid = (lo + hi) / 2
            oklabToLinear(mid, a, b, rgb)
            if (luminance(rgb) < targetY) lo = mid else hi = mid
        }
        val l = (lo + hi) / 2
        oklabToLinear(l, a, b, rgb)
        return l
    }

    /**
     * 给定色相、期望彩度和色调，求一个 sRGB 颜色。
     * 期望彩度超出 sRGB 色域时，在保持色调不变的前提下降低彩度。
     */
    fun fromHueChromaTone(hue: Double, chroma: Double, tone: Double): Color {
        if (tone <= 0.0) return Color.Black
        if (tone >= 100.0) return Color.White
        val targetY = toneToY(tone)
        val rgb = DoubleArray(3)
        solveLightness(hue, chroma, targetY, rgb)
        if (!inGamut(rgb)) {
            var lo = 0.0
            var hi = chroma
            repeat(30) {
                val mid = (lo + hi) / 2
                solveLightness(hue, mid, targetY, rgb)
                if (inGamut(rgb)) lo = mid else hi = mid
            }
            solveLightness(hue, lo, targetY, rgb)
        }
        return Color(
            red = delinearize(rgb[0].coerceIn(0.0, 1.0)).toFloat(),
            green = delinearize(rgb[1].coerceIn(0.0, 1.0)).toFloat(),
            blue = delinearize(rgb[2].coerceIn(0.0, 1.0)).toFloat(),
        )
    }
}

/** 一个色相、一个彩度下从 0 到 100 的色调序列。 */
internal class TonalPalette(private val hue: Double, private val chroma: Double) {
    private val cache = HashMap<Int, Color>()

    fun tone(t: Int): Color = cache.getOrPut(t) { ColorMath.fromHueChromaTone(hue, chroma, t.toDouble()) }
}
