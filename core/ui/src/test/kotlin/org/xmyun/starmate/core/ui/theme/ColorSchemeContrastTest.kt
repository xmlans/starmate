package org.xmyun.starmate.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ColorSchemeContrastTest {

    // 独立于实现的 WCAG 2.x 对比度计算。
    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color) =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun hsvHue(c: Color): Float {
        val r = c.red
        val g = c.green
        val b = c.blue
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        if (d == 0f) return 0f
        val h = when (mx) {
            r -> 60 * (((g - b) / d) % 6)
            g -> 60 * (((b - r) / d) + 2)
            else -> 60 * (((r - g) / d) + 4)
        }
        return (h + 360) % 360
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    /** 题目要求的四对正文颜色。 */
    private fun requiredPairs(s: ColorScheme) = listOf(
        "onSurface/surface" to (s.onSurface to s.surface),
        "onPrimary/primary" to (s.onPrimary to s.primary),
        "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
    )

    /** 其余同样会承载文字的颜色对。 */
    private fun extraPairs(s: ColorScheme) = listOf(
        "onBackground/background" to (s.onBackground to s.background),
        "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
        "onSurfaceVariant/surfaceContainerHighest" to (s.onSurfaceVariant to s.surfaceContainerHighest),
        "onSurface/surfaceContainerHighest" to (s.onSurface to s.surfaceContainerHighest),
        "primary/surface" to (s.primary to s.surface),
        "primary/surfaceContainerHigh" to (s.primary to s.surfaceContainerHigh),
        "onSecondary/secondary" to (s.onSecondary to s.secondary),
        "onTertiary/tertiary" to (s.onTertiary to s.tertiary),
        "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
        "onError/error" to (s.onError to s.error),
        "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
        "error/surfaceContainerHigh" to (s.error to s.surfaceContainerHigh),
        "inverseOnSurface/inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
        "onOutgoingBubble/outgoingBubble" to (s.onOutgoingBubble to s.outgoingBubble),
        "onIncomingBubble/incomingBubble" to (s.onIncomingBubble to s.incomingBubble),
    )

    private fun assertPairs(pairs: (ColorScheme) -> List<Pair<String, Pair<Color, Color>>>) {
        val failures = mutableListOf<String>()
        var checked = 0
        for (accent in ThemeAccent.entries) {
            for (dark in listOf(false, true)) {
                val scheme = starmateColorScheme(accent, dark)
                for ((name, colors) in pairs(scheme)) {
                    val ratio = contrast(colors.first, colors.second)
                    checked++
                    if (ratio < 4.5) {
                        failures += "${accent.name} ${if (dark) "dark" else "light"} $name = ${"%.2f".format(ratio)}"
                    }
                }
            }
        }
        assertTrue("checked nothing", checked > 0)
        assertTrue("contrast below 4.5:1 ->\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun requiredTextPairs_meet4_5_forAllSeedsLightAndDark() {
        assertEquals(5, ThemeAccent.entries.size)
        assertPairs(::requiredPairs)
    }

    @Test
    fun otherTextPairs_meet4_5_forAllSeedsLightAndDark() {
        assertPairs(::extraPairs)
    }

    @Test
    fun paleSeed_isNotUsedAsLightPrimary() {
        val pink = ThemeAccent.PINK.seed
        // 浅粉本身压不住白字，所以浅色方案的主色必须是派生出的深色。
        assertTrue(contrast(pink, Color.White) < 4.5)
        val light = starmateColorScheme(ThemeAccent.PINK, darkTheme = false)
        assertNotEquals(pink, light.primary)
        assertTrue(luminance(light.primary) < luminance(pink))
    }

    @Test
    fun derivedPrimary_keepsSeedHue() {
        for (accent in ThemeAccent.entries) {
            for (dark in listOf(false, true)) {
                val primary = starmateColorScheme(accent, dark).primary
                val d = hueDistance(hsvHue(accent.seed), hsvHue(primary))
                assertTrue("${accent.name} dark=$dark hue drift $d", d < 25f)
            }
        }
    }

    @Test
    fun wholeSchemeFollowsSeed_notJustPrimary() {
        val pink = starmateColorScheme(ThemeAccent.PINK, darkTheme = false)
        val blue = starmateColorScheme(ThemeAccent.BLUE, darkTheme = false)
        assertNotEquals(pink.primary, blue.primary)
        assertNotEquals(pink.secondaryContainer, blue.secondaryContainer)
        assertNotEquals(pink.tertiary, blue.tertiary)
        assertNotEquals(pink.surfaceContainerHigh, blue.surfaceContainerHigh)
    }

    @Test
    fun darkScheme_hasDarkSurfaceAndLightText() {
        for (accent in ThemeAccent.entries) {
            val dark = starmateColorScheme(accent, darkTheme = true)
            assertTrue(luminance(dark.surface) < 0.02)
            assertTrue(luminance(dark.onSurface) > 0.6)
            val light = starmateColorScheme(accent, darkTheme = false)
            assertTrue(luminance(light.surface) > 0.9)
        }
    }

    @Test
    fun fromName_fallsBackToPink() {
        assertEquals(ThemeAccent.PINK, ThemeAccent.fromName(null))
        assertEquals(ThemeAccent.PINK, ThemeAccent.fromName("NEON"))
        assertEquals(ThemeAccent.GREEN, ThemeAccent.fromName("GREEN"))
    }
}
