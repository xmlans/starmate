package org.xmyun.starmate.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.xmyun.starmate.core.ui.R

/**
 * 标题字体：站酷快乐体。圆胖俏皮，用在页面标题、她的名字、按钮、分组小标题这些短文字上。
 * 只有一个字重，只收常用的 6763 个汉字，缺的字系统会自动换成默认字体。
 */
val DisplayFontFamily = FontFamily(Font(R.font.zcool_kuaile, FontWeight.Normal))

/**
 * 正文字体：寒蝉半圆体。笔画端点是圆的，长段文字也好读，用在聊天气泡、说明文字、列表上。
 * 只有一个字重。
 */
val BodyFontFamily = FontFamily(Font(R.font.chill_round_m, FontWeight.Normal))

private val Base = Typography()

private fun TextStyle.display(size: TextUnit, lineHeight: TextUnit) = copy(
    fontFamily = DisplayFontFamily,
    // 快乐体本身笔画就粗，不再加粗；系统合成的粗体会让它糊成一团。
    fontWeight = FontWeight.Normal,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = 0.sp,
)

private fun TextStyle.body(size: TextUnit, lineHeight: TextUnit, letterSpacing: TextUnit) = copy(
    fontFamily = BodyFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
)

/**
 * 两种字体各管一类文字：短的、起标识作用的用快乐体，要读的用圆体。
 * 中文方块字比拉丁字母显得密，正文行高比 Material 默认放宽约 2–4sp，字距收小。
 * 单位都是 sp，跟随系统字体放大。
 *
 * 聊天气泡正文用 bodyLarge，列表摘要用 bodyMedium；按钮和菜单项用 labelLarge。
 */
val StarmateTypography: Typography = Base.copy(
    displayLarge = Base.displayLarge.display(54.sp, 64.sp),
    displayMedium = Base.displayMedium.display(44.sp, 54.sp),
    displaySmall = Base.displaySmall.display(36.sp, 46.sp),
    headlineLarge = Base.headlineLarge.display(31.sp, 42.sp),
    headlineMedium = Base.headlineMedium.display(27.sp, 38.sp),
    headlineSmall = Base.headlineSmall.display(24.sp, 34.sp),
    titleLarge = Base.titleLarge.display(21.sp, 30.sp),
    titleMedium = Base.titleMedium.display(17.sp, 25.sp),
    titleSmall = Base.titleSmall.body(14.sp, 22.sp, 0.1.sp),
    bodyLarge = Base.bodyLarge.body(16.sp, 26.sp, 0.15.sp),
    bodyMedium = Base.bodyMedium.body(14.sp, 22.sp, 0.1.sp),
    bodySmall = Base.bodySmall.body(12.sp, 18.sp, 0.2.sp),
    labelLarge = Base.labelLarge.display(15.sp, 21.sp),
    labelMedium = Base.labelMedium.body(12.sp, 18.sp, 0.2.sp),
    labelSmall = Base.labelSmall.body(11.sp, 16.sp, 0.2.sp),
)
