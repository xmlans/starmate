package org.xmyun.starmate.feature.tools.common

import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** 每组的系统权限是否已授予。真实实现见 AndroidGroupPermissions。 */
interface GroupPermissions {
    suspend fun isGranted(group: ToolGroup): Boolean
}

/**
 * 工具和环境信息的总闸门。
 * - usable：已启用且权限已授予。位置组在没有定位权限但手填了城市时也算可用。
 * - ambientOn：usable 且选了“让她随时知道”。
 */
class GroupGate @Inject constructor(
    private val prefs: ToolsPrefs,
    private val permissions: GroupPermissions,
) {
    suspend fun granted(group: ToolGroup): Boolean {
        if (permissions.isGranted(group)) return true
        return group == ToolGroup.LOCATION && prefs.manualCity.first() != null
    }

    suspend fun usable(group: ToolGroup): Boolean =
        prefs.current(group).enabled && granted(group)

    suspend fun ambientOn(group: ToolGroup): Boolean {
        if (!group.hasAmbient) return false
        val settings = prefs.current(group)
        return settings.enabled && settings.ambient && granted(group)
    }

    suspend fun status(group: ToolGroup): GroupStatus {
        val settings = prefs.current(group)
        return when {
            !settings.enabled -> GroupStatus.OFF
            !granted(group) -> GroupStatus.MISSING_PERMISSION
            settings.ambient && group.hasAmbient -> GroupStatus.AMBIENT
            else -> GroupStatus.ON_DEMAND
        }
    }
}
