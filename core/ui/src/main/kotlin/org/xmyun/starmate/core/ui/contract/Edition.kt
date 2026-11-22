package org.xmyun.starmate.core.ui.contract

import androidx.compose.runtime.staticCompositionLocalOf
import org.xmyun.starmate.core.model.AppEdition

/**
 * 界面里读当前版本的能力开关（例如要不要显示保活开关、举报入口）。由 :app 的 MainActivity 提供；
 * 预览和测试里没有提供时取 [AppEdition.Default]。
 */
val LocalAppEdition = staticCompositionLocalOf { AppEdition.Default }
