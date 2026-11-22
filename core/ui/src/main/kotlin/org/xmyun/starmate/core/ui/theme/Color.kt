package org.xmyun.starmate.core.ui.theme

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.xmyun.starmate.core.ui.R

/** 可选主题色。seed 是派生配色方案用的种子色，本身很浅，不要直接拿来当文字色或白字的底色。 */
enum class ThemeAccent(val seed: Color, @StringRes val label: Int) {
    PINK(Color(0xFFF4A7B9), R.string.core_accent_pink),
    BLUE(Color(0xFF9DC1EC), R.string.core_accent_blue),
    PURPLE(Color(0xFFBFA9E6), R.string.core_accent_purple),
    GREEN(Color(0xFFA3D3B5), R.string.core_accent_green),
    ORANGE(Color(0xFFF5BB8F), R.string.core_accent_orange),
    ;

    companion object {
        val Default = PINK

        /** 按名字解析，未知或为空时回到默认浅粉。 */
        fun fromName(name: String?): ThemeAccent = entries.firstOrNull { it.name == name } ?: Default
    }
}

/**
 * 从种子色派生完整的 Material 3 配色方案。
 *
 * 主色沿用种子的色相，彩度收在柔和的范围；次要色同色相低彩度；第三色色相偏移 60°；
 * 中性色带一点种子色相，页面底色因此是很淡的主题色而不是灰白。各角色取 Material 标准色调，
 * 浅色方案的主色是色调 40（比种子深得多），所以白字能清楚地压在主色上。
 *
 * 浅色方案里页面底色是色调 97，卡片（surfaceContainerLow）是纯白，卡片比底色亮，
 * 和系统设置、Telegram 的分组列表一个思路；深色方案保持 Material 的顺序，卡片比底色亮一档。
 */
fun starmateColorScheme(seed: Color, darkTheme: Boolean): ColorScheme {
    val (_, seedChroma, hue) = ColorMath.toOklch(seed).let { Triple(it[0], it[1], it[2]) }
    val primary = TonalPalette(hue, seedChroma.coerceIn(0.10, 0.14))
    val secondary = TonalPalette(hue, 0.035)
    val tertiary = TonalPalette(hue + Math.toRadians(60.0), 0.06)
    val neutral = TonalPalette(hue, 0.012)
    val neutralVariant = TonalPalette(hue, 0.02)
    val error = TonalPalette(Math.toRadians(27.0), 0.17)

    return if (!darkTheme) {
        lightColorScheme(
            primary = primary.tone(40),
            onPrimary = primary.tone(100),
            primaryContainer = primary.tone(90),
            onPrimaryContainer = primary.tone(30),
            inversePrimary = primary.tone(80),
            secondary = secondary.tone(40),
            onSecondary = secondary.tone(100),
            secondaryContainer = secondary.tone(90),
            onSecondaryContainer = secondary.tone(30),
            tertiary = tertiary.tone(40),
            onTertiary = tertiary.tone(100),
            tertiaryContainer = tertiary.tone(90),
            onTertiaryContainer = tertiary.tone(30),
            background = neutral.tone(97),
            onBackground = neutral.tone(10),
            surface = neutral.tone(97),
            onSurface = neutral.tone(10),
            surfaceVariant = neutralVariant.tone(90),
            onSurfaceVariant = neutralVariant.tone(30),
            surfaceTint = primary.tone(40),
            inverseSurface = neutral.tone(20),
            inverseOnSurface = neutral.tone(95),
            error = error.tone(40),
            onError = error.tone(100),
            errorContainer = error.tone(90),
            onErrorContainer = error.tone(30),
            outline = neutralVariant.tone(50),
            outlineVariant = neutralVariant.tone(80),
            scrim = Color.Black,
            surfaceBright = neutral.tone(99),
            surfaceContainer = neutral.tone(95),
            surfaceContainerHigh = neutral.tone(94),
            surfaceContainerHighest = neutral.tone(91),
            surfaceContainerLow = neutral.tone(100),
            surfaceContainerLowest = neutral.tone(100),
            surfaceDim = neutral.tone(88),
            primaryFixed = primary.tone(90),
            primaryFixedDim = primary.tone(80),
            onPrimaryFixed = primary.tone(10),
            onPrimaryFixedVariant = primary.tone(30),
            secondaryFixed = secondary.tone(90),
            secondaryFixedDim = secondary.tone(80),
            onSecondaryFixed = secondary.tone(10),
            onSecondaryFixedVariant = secondary.tone(30),
            tertiaryFixed = tertiary.tone(90),
            tertiaryFixedDim = tertiary.tone(80),
            onTertiaryFixed = tertiary.tone(10),
            onTertiaryFixedVariant = tertiary.tone(30),
        )
    } else {
        darkColorScheme(
            primary = primary.tone(80),
            onPrimary = primary.tone(20),
            primaryContainer = primary.tone(30),
            onPrimaryContainer = primary.tone(90),
            inversePrimary = primary.tone(40),
            secondary = secondary.tone(80),
            onSecondary = secondary.tone(20),
            secondaryContainer = secondary.tone(30),
            onSecondaryContainer = secondary.tone(90),
            tertiary = tertiary.tone(80),
            onTertiary = tertiary.tone(20),
            tertiaryContainer = tertiary.tone(30),
            onTertiaryContainer = tertiary.tone(90),
            background = neutral.tone(6),
            onBackground = neutral.tone(90),
            surface = neutral.tone(6),
            onSurface = neutral.tone(90),
            surfaceVariant = neutralVariant.tone(30),
            onSurfaceVariant = neutralVariant.tone(80),
            surfaceTint = primary.tone(80),
            inverseSurface = neutral.tone(90),
            inverseOnSurface = neutral.tone(20),
            error = error.tone(80),
            onError = error.tone(20),
            errorContainer = error.tone(30),
            onErrorContainer = error.tone(90),
            outline = neutralVariant.tone(60),
            outlineVariant = neutralVariant.tone(30),
            scrim = Color.Black,
            surfaceBright = neutral.tone(24),
            surfaceContainer = neutral.tone(13),
            surfaceContainerHigh = neutral.tone(17),
            surfaceContainerHighest = neutral.tone(22),
            surfaceContainerLow = neutral.tone(11),
            surfaceContainerLowest = neutral.tone(4),
            surfaceDim = neutral.tone(6),
            primaryFixed = primary.tone(90),
            primaryFixedDim = primary.tone(80),
            onPrimaryFixed = primary.tone(10),
            onPrimaryFixedVariant = primary.tone(30),
            secondaryFixed = secondary.tone(90),
            secondaryFixedDim = secondary.tone(80),
            onSecondaryFixed = secondary.tone(10),
            onSecondaryFixedVariant = secondary.tone(30),
            tertiaryFixed = tertiary.tone(90),
            tertiaryFixedDim = tertiary.tone(80),
            onTertiaryFixed = tertiary.tone(10),
            onTertiaryFixedVariant = tertiary.tone(30),
        )
    }
}

fun starmateColorScheme(accent: ThemeAccent, darkTheme: Boolean): ColorScheme =
    starmateColorScheme(accent.seed, darkTheme)

/** 这套配色是不是浅色方案。 */
val ColorScheme.isLight: Boolean get() = surface.luminance() > 0.5f

// 聊天气泡配色。气泡压在壁纸上，所以都比壁纸亮：她的消息是白色，用户自己的消息带一点主题色。

/** 用户自己发出的气泡底色。 */
val ColorScheme.outgoingBubble: Color
    get() = if (isLight) lerp(surfaceContainerLowest, primaryContainer, 0.55f) else primaryContainer

/** 用户气泡上的文字色。 */
val ColorScheme.onOutgoingBubble: Color get() = onPrimaryContainer

/** 她发来的气泡底色。 */
val ColorScheme.incomingBubble: Color get() = if (isLight) surfaceContainerLowest else surfaceContainerHighest

/** 她的气泡上的文字色。 */
val ColorScheme.onIncomingBubble: Color get() = onSurface

// 聊天壁纸：从主题色过渡到偏移 60° 的邻近色，浅色方案是粉彩，深色方案是带一点颜色的夜空。

/** 壁纸渐变的上端。 */
val ColorScheme.wallpaperTop: Color
    get() = if (isLight) lerp(primaryContainer, primaryFixedDim, 0.2f) else lerp(surface, primaryContainer, 0.38f)

/** 壁纸渐变的下端。 */
val ColorScheme.wallpaperBottom: Color
    get() = if (isLight) lerp(tertiaryContainer, tertiaryFixedDim, 0.2f) else lerp(surface, tertiaryContainer, 0.34f)

/** 壁纸上星星的颜色。 */
val ColorScheme.wallpaperStar: Color
    get() = if (isLight) Color.White.copy(alpha = 0.6f) else primary.copy(alpha = 0.16f)

// 毛玻璃：模糊后的内容上盖一层半透明的页面底色，再描一圈很淡的高光边。

/** 盖在模糊内容上的那层颜色。 */
val ColorScheme.glassTint: Color get() = surface.copy(alpha = if (isLight) 0.66f else 0.6f)

/** 系统不支持模糊（Android 11 及以下）时改用的底色，透明度更低，保证上面的字看得清。 */
val ColorScheme.glassFallback: Color get() = surface.copy(alpha = 0.94f)

/** 毛玻璃的高光描边。 */
val ColorScheme.glassBorder: Color get() = Color.White.copy(alpha = if (isLight) 0.7f else 0.1f)

/** 悬浮元素（底部标签栏、输入框）的投影色，带一点主题色，比纯黑柔和。 */
val ColorScheme.softShadow: Color get() = if (isLight) lerp(Color.Black, primary, 0.45f) else Color.Black
