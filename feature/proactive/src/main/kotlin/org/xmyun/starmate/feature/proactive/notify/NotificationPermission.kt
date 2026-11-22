package org.xmyun.starmate.feature.proactive.notify

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.content.ContextCompat

object NotificationPermission {
    /** POST_NOTIFICATIONS 只在 Android 13+ 需要运行时授权，更低版本视为已授权。 */
    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 打开本应用的通知设置页，失败时退回应用详情页。 */
    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            openAppDetails(context)
        }
    }

    fun openAppDetails(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            // 没有可用的设置页，什么也不做
        }
    }
}

/** 请求通知权限。Android 13 以下直接回调 true。 */
@Composable
fun rememberNotificationPermissionRequester(onResult: (Boolean) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onResult)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return remember { { callback.value(true) } }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        callback.value(granted)
    }
    return remember(launcher) { { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) } }
}
