package org.xmyun.starmate.feature.tools.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import org.xmyun.starmate.feature.tools.common.AndroidGroupPermissions
import org.xmyun.starmate.feature.tools.common.ToolGroup
import org.xmyun.starmate.feature.tools.health.HealthConnectGateway
import org.xmyun.starmate.feature.tools.music.MediaListenerService

/**
 * 返回一个触发某组授权流程的函数。流程结束后调用 onDone，由调用方重新检查是否已授权。
 * - 位置：一次请求粗略和精确定位，由用户在系统弹窗里选精度。
 * - 健康：Health Connect 可用时先请求睡眠和步数读权限，再请求活动识别（计步器兜底）。
 * - 音乐：打开通知使用权设置页。
 */
@Composable
fun rememberGroupPermissionRequester(
    group: ToolGroup,
    healthConnectAvailable: Boolean,
    onDone: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val currentOnDone by rememberUpdatedState(onDone)
    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        currentOnDone()
    }
    val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        val extra = AndroidGroupPermissions.runtimePermissions(ToolGroup.HEALTH)
        if (extra.isNotEmpty()) runtimeLauncher.launch(extra) else currentOnDone()
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        currentOnDone()
    }
    return remember(group, healthConnectAvailable, context, runtimeLauncher, healthLauncher, settingsLauncher) {
        {
            when (group) {
                ToolGroup.ALARM -> currentOnDone()
                ToolGroup.MUSIC -> settingsLauncher.launch(context.notificationListenerSettingsIntent())
                ToolGroup.HEALTH -> if (healthConnectAvailable) {
                    healthLauncher.launch(HealthConnectGateway.PERMISSIONS)
                } else {
                    val extra = AndroidGroupPermissions.runtimePermissions(ToolGroup.HEALTH)
                    if (extra.isNotEmpty()) runtimeLauncher.launch(extra) else currentOnDone()
                }
                else -> runtimeLauncher.launch(AndroidGroupPermissions.runtimePermissions(group))
            }
        }
    }
}

/** Android 11+ 直接打开星伴这一项的通知使用权详情页，打不开时退回列表页。 */
fun Context.notificationListenerSettingsIntent(): Intent {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val component = ComponentName(this, MediaListenerService::class.java)
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        if (detail.resolveActivity(packageManager) != null) return detail
    }
    return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
}

fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
