package org.xmyun.starmate.feature.mobileuse.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 星伴的无障碍服务。它自己不做任何事：只在连接时登记到 [AccessibilityBridge]，
 * 由任务循环通过 [AccessibilityDeviceController] 在守卫放行后按需读取和操作。
 * 收到的事件只用来记录“界面在变化”的时间，不读事件内容。
 */
@AndroidEntryPoint
class StarmateAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var bridge: AccessibilityBridge

    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) bridge.screenOff()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 截图等能力需要 Android 11+；更低版本直接关掉自己
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            disableSelf()
            return
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        bridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        bridge.onWindowEvent()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        if (::bridge.isInitialized) bridge.detach(this)
    }
}
