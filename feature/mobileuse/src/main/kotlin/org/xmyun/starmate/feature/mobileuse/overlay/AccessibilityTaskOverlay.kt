package org.xmyun.starmate.feature.mobileuse.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.xmyun.starmate.feature.mobileuse.R
import org.xmyun.starmate.feature.mobileuse.service.AccessibilityBridge

/**
 * 用 TYPE_ACCESSIBILITY_OVERLAY 窗口实现的悬浮层：常驻的“星伴正在操作 · 停止”条（ControlOverlay）
 * 和“是 / 否”确认框（ConfirmOverlay）。只能在真机上验证。
 */
@Singleton
class AccessibilityTaskOverlay @Inject constructor(
    private val bridge: AccessibilityBridge,
) : TaskOverlay {

    private var bar: View? = null
    private var dialog: View? = null

    override suspend fun showRunning(onStop: () -> Unit) = withContext(Dispatchers.Main) {
        val svc = bridge.service.value ?: error("accessibility service not connected")
        removeBar()
        val view = ControlOverlay.create(svc, onStop)
        windowManager(svc).addView(view, ControlOverlay.layoutParams())
        bar = view
    }

    override suspend fun hide() = withContext(Dispatchers.Main) {
        removeDialog()
        removeBar()
    }

    override suspend fun confirm(message: String): Boolean = withContext(Dispatchers.Main) {
        val svc = bridge.service.value ?: return@withContext false
        removeDialog()
        try {
            suspendCancellableCoroutine { cont ->
                val view = ConfirmOverlay.create(svc, message) { answer ->
                    if (cont.isActive) cont.resume(answer)
                }
                try {
                    windowManager(svc).addView(view, ConfirmOverlay.layoutParams())
                    dialog = view
                } catch (e: RuntimeException) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        } finally {
            removeDialog()
        }
    }

    private fun removeBar() {
        val view = bar ?: return
        bar = null
        runCatching { windowManager(view.context).removeView(view) }
    }

    private fun removeDialog() {
        val view = dialog ?: return
        dialog = null
        runCatching { windowManager(view.context).removeView(view) }
    }

    private fun windowManager(context: Context) = context.getSystemService(WindowManager::class.java)
}

internal fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}

/** 常驻悬浮条。 */
internal object ControlOverlay {
    fun create(context: Context, onStop: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.parseColor("#E6332A30"), context.dp(24).toFloat())
            setPadding(context.dp(16), context.dp(6), context.dp(6), context.dp(6))
        }
        row.addView(
            TextView(context).apply {
                text = context.getString(R.string.mobileuse_overlay_running)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            },
        )
        row.addView(
            Button(context).apply {
                text = context.getString(R.string.mobileuse_overlay_stop)
                isAllCaps = false
                setTextColor(Color.parseColor("#332A30"))
                background = rounded(Color.parseColor("#FFD6E4"), context.dp(18).toFloat())
                minHeight = context.dp(36)
                minimumHeight = context.dp(36)
                setPadding(context.dp(16), 0, context.dp(16), 0)
                setOnClickListener { onStop() }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dp(12)
            },
        )
        return row
    }

    fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = 96
    }
}

/** 只有“是 / 否”的确认框。 */
internal object ConfirmOverlay {
    fun create(context: Context, message: String, onAnswer: (Boolean) -> Unit): View {
        var answered = false
        fun answer(value: Boolean) {
            if (answered) return
            answered = true
            onAnswer(value)
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, context.dp(28).toFloat())
            setPadding(context.dp(24), context.dp(20), context.dp(24), context.dp(12))
        }
        card.addView(
            TextView(context).apply {
                text = context.getString(R.string.mobileuse_overlay_confirm_title)
                setTextColor(Color.parseColor("#332A30"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            },
        )
        card.addView(
            TextView(context).apply {
                text = message
                setTextColor(Color.parseColor("#4D4448"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, context.dp(12), 0, context.dp(12))
            },
        )
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(
            Button(context).apply {
                text = context.getString(R.string.mobileuse_overlay_no)
                isAllCaps = false
                setOnClickListener { answer(false) }
            },
        )
        buttons.addView(
            Button(context).apply {
                text = context.getString(R.string.mobileuse_overlay_yes)
                isAllCaps = false
                setOnClickListener { answer(true) }
            },
        )
        card.addView(buttons)
        return card
    }

    fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.CENTER
        dimAmount = 0.4f
        horizontalMargin = 0.05f
    }
}
