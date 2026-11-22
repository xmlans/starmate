package org.xmyun.starmate.feature.mobileuse.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.xmyun.starmate.feature.mobileuse.guard.ApprovedAction
import org.xmyun.starmate.feature.mobileuse.guard.ObservePermit
import org.xmyun.starmate.feature.mobileuse.model.AgentAction
import org.xmyun.starmate.feature.mobileuse.model.Foreground
import org.xmyun.starmate.feature.mobileuse.model.RawObservation
import org.xmyun.starmate.feature.mobileuse.model.ScreenState
import org.xmyun.starmate.feature.mobileuse.model.ScrollDirection
import org.xmyun.starmate.feature.mobileuse.model.UiNode
import org.xmyun.starmate.feature.mobileuse.tree.NodeIdentity
import org.xmyun.starmate.feature.mobileuse.tree.NodeMapper

/**
 * 基于无障碍服务的 [DeviceController]。只能在真机上验证。
 * 执行前再做一层核对（快照、前台包名、目标节点刷新后是否还是同一个、是否变成了密码框），
 * 尽量缩小守卫检查和真正执行之间的时间差。
 */
@Singleton
class AccessibilityDeviceController @Inject constructor(
    private val bridge: AccessibilityBridge,
    private val labels: AppLabels,
) : DeviceController {

    private class Snapshot(val id: Long, val packageName: String, val handles: Map<Int, AccessibilityNodeInfo>)

    private val lock = Mutex()
    private var snapshotCounter = 0L
    private var snapshot: Snapshot? = null

    private val service: StarmateAccessibilityService? get() = bridge.service.value

    override val isConnected: Boolean get() = service != null

    override val interruptions: Flow<Interruption> get() = bridge.interruptions

    override suspend fun foreground(): Foreground? = withContext(Dispatchers.Default) {
        val svc = service ?: return@withContext null
        val pkg = svc.rootInActiveWindow?.packageName?.toString() ?: return@withContext null
        Foreground(packageName = pkg, appLabel = labels.labelOf(pkg), otherAppPackages = otherAppPackages(svc, pkg))
    }

    override suspend fun readScreen(permit: ObservePermit, withScreenshot: Boolean): RawObservation? =
        lock.withLock {
            withContext(Dispatchers.Default) {
                val svc = service ?: return@withContext null
                if (!permit.isValid) return@withContext null
                val root = svc.rootInActiveWindow ?: return@withContext null
                val pkg = root.packageName?.toString()
                if (pkg != permit.packageName) return@withContext null
                val mapped = NodeMapper.map(root)
                val id = ++snapshotCounter
                snapshot = Snapshot(id, pkg, mapped.handles)
                val screen = ScreenState(
                    packageName = pkg,
                    appLabel = labels.labelOf(pkg),
                    nodes = mapped.nodes,
                    passwordKeyboardShown = passwordKeyboardShown(svc, mapped.nodes),
                    otherAppPackages = otherAppPackages(svc, pkg),
                    snapshotId = id,
                )
                val shot = if (withScreenshot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    screenshot(svc)
                } else {
                    null
                }
                RawObservation(screen, shot)
            }
        }

    override suspend fun perform(approved: ApprovedAction): Boolean = lock.withLock {
        if (!approved.consume()) return@withLock false
        val svc = service ?: return@withLock false
        val currentPkg = withContext(Dispatchers.Default) { svc.rootInActiveWindow?.packageName?.toString() }
        if (currentPkg != approved.packageName) return@withLock false
        when (val action = approved.action) {
            is AgentAction.Back -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            is AgentAction.Home -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            is AgentAction.OpenApp -> openApp(svc, action.packageName)
            is AgentAction.Targeted -> {
                val node = resolveTarget(approved) ?: return@withLock false
                when (action) {
                    is AgentAction.Tap -> click(svc, node, long = false)
                    is AgentAction.LongPress -> click(svc, node, long = true)
                    is AgentAction.InputText -> {
                        // 守卫之外再挡一次：刷新后变成密码框就不输入
                        if (node.isPassword || !node.isEditable) return@withLock false
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                        }
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    }
                    is AgentAction.Scroll -> scroll(svc, node, action.direction)
                }
            }
            else -> false
        }
    }

    override suspend fun awaitSettled(timeoutMs: Long) {
        val start = SystemClock.elapsedRealtime()
        delay(SETTLE_QUIET_MS)
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (SystemClock.elapsedRealtime() - bridge.lastEventAtMs >= SETTLE_QUIET_MS) return
            delay(100)
        }
    }

    // ---- 内部 ----

    /** 找到放行时的那个节点，刷新后确认它还是同一个元素。 */
    private fun resolveTarget(approved: ApprovedAction): AccessibilityNodeInfo? {
        val target = approved.target ?: return null
        val snap = snapshot ?: return null
        if (snap.id != approved.snapshotId || snap.packageName != approved.packageName) return null
        val node = snap.handles[target.id] ?: return null
        if (!node.refresh()) return null
        val refreshed: UiNode = NodeMapper.toUiNode(node, target.id)
        return if (NodeIdentity.same(refreshed, target)) node else null
    }

    private suspend fun click(svc: AccessibilityService, node: AccessibilityNodeInfo, long: Boolean): Boolean {
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val supported = if (long) node.isLongClickable else node.isClickable
        if (supported && node.performAction(action)) return true
        // 节点自己不响应点击时，在它的中心点发一个手势；中心点必须落在当前活动窗口里
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.isEmpty || !insideActiveWindow(svc, rect.centerX(), rect.centerY())) return false
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val duration = if (long) 700L else 60L
        return dispatch(svc, GestureDescription.StrokeDescription(path, 0, duration))
    }

    private suspend fun scroll(svc: AccessibilityService, node: AccessibilityNodeInfo, direction: ScrollDirection): Boolean {
        val forward = direction == ScrollDirection.DOWN || direction == ScrollDirection.RIGHT
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (node.isScrollable && node.performAction(action)) return true
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.width() < 40 || rect.height() < 40) return false
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val dx = rect.width() * 0.3f
        val dy = rect.height() * 0.3f
        val path = Path().apply {
            when (direction) {
                ScrollDirection.DOWN -> { moveTo(cx, cy + dy); lineTo(cx, cy - dy) }
                ScrollDirection.UP -> { moveTo(cx, cy - dy); lineTo(cx, cy + dy) }
                ScrollDirection.RIGHT -> { moveTo(cx + dx, cy); lineTo(cx - dx, cy) }
                ScrollDirection.LEFT -> { moveTo(cx - dx, cy); lineTo(cx + dx, cy) }
            }
        }
        return dispatch(svc, GestureDescription.StrokeDescription(path, 0, 350))
    }

    private suspend fun dispatch(svc: AccessibilityService, stroke: GestureDescription.StrokeDescription): Boolean =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val gesture = GestureDescription.Builder().addStroke(stroke).build()
                    val ok = svc.dispatchGesture(
                        gesture,
                        object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (cont.isActive) cont.resume(false)
                            }
                        },
                        null,
                    )
                    if (!ok && cont.isActive) cont.resume(false)
                }
            } ?: false
        }

    private fun openApp(svc: AccessibilityService, packageName: String): Boolean {
        val intent = svc.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            svc.startActivity(intent)
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun insideActiveWindow(svc: AccessibilityService, x: Int, y: Int): Boolean {
        val active = svc.windows.firstOrNull { it.isActive } ?: return false
        val bounds = Rect().also { active.getBoundsInScreen(it) }
        return bounds.contains(x, y)
    }

    /** 同屏可见的其他应用窗口（分屏、小窗）的包名，不含输入法、系统栏和无障碍悬浮层。 */
    private fun otherAppPackages(svc: AccessibilityService, activePkg: String): Set<String> =
        svc.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !it.isActive }
            .mapNotNull { it.root?.packageName?.toString() }
            .filter { it != activePkg }
            .toSet()

    /** 输入法弹出且焦点在密码框上，视为密码键盘。 */
    private fun passwordKeyboardShown(svc: AccessibilityService, nodes: List<UiNode>): Boolean {
        val imeVisible = svc.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (!imeVisible) return false
        val focusedInput = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return focusedInput?.isPassword == true || nodes.any { it.focused && it.isPassword }
    }

    @SuppressLint("NewApi")
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun screenshot(svc: AccessibilityService): String? = withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                svc.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val encoded = runCatching { encode(result) }.getOrNull()
                        if (cont.isActive) cont.resume(encoded)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun encode(result: AccessibilityService.ScreenshotResult): String? {
        val buffer = result.hardwareBuffer
        try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle()
            val scale = SCREENSHOT_MAX_WIDTH.toFloat() / soft.width
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(soft, SCREENSHOT_MAX_WIDTH, (soft.height * scale).toInt(), true)
                    .also { soft.recycle() }
            } else {
                soft
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            scaled.recycle()
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            buffer.close()
        }
    }

    companion object {
        private const val SETTLE_QUIET_MS = 500L
        private const val GESTURE_TIMEOUT_MS = 3_000L
        private const val SCREENSHOT_TIMEOUT_MS = 3_000L
        private const val SCREENSHOT_MAX_WIDTH = 720
    }
}
