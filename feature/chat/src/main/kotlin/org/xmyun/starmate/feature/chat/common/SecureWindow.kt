package org.xmyun.starmate.feature.chat.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** 页面在屏时给窗口加 FLAG_SECURE（禁止截屏、录屏、最近任务缩略图），离开时去掉。输入 key 的页面用。 */
@Composable
fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
