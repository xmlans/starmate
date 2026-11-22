package org.xmyun.starmate.feature.tools.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.xmyun.starmate.feature.tools.health.HealthGateway
import org.xmyun.starmate.feature.tools.music.MediaListenerService

class AndroidGroupPermissions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val health: HealthGateway,
) : GroupPermissions {

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override suspend fun isGranted(group: ToolGroup): Boolean = when (group) {
        ToolGroup.CALENDAR -> granted(Manifest.permission.READ_CALENDAR) && granted(Manifest.permission.WRITE_CALENDAR)
        // SET_ALARM 是普通权限，安装即有
        ToolGroup.ALARM -> true
        ToolGroup.CONTACTS -> granted(Manifest.permission.READ_CONTACTS)
        // 只给粗略位置也算已授权
        ToolGroup.LOCATION -> granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)
        // Health Connect 任一读权限，或者手机计步器可用
        ToolGroup.HEALTH -> health.granted().isNotEmpty() || health.stepCounterUsable()
        ToolGroup.MUSIC -> MediaListenerService.isEnabled(context)
    }

    companion object {
        fun runtimePermissions(group: ToolGroup): Array<String> = when (group) {
            ToolGroup.CALENDAR -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            ToolGroup.CONTACTS -> arrayOf(Manifest.permission.READ_CONTACTS)
            ToolGroup.LOCATION -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            ToolGroup.HEALTH -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyArray()
            else -> emptyArray()
        }
    }
}
