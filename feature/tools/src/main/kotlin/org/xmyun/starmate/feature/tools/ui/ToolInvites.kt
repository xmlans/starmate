package org.xmyun.starmate.feature.tools.ui

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.xmyun.starmate.core.ui.contract.PermissionInvite
import org.xmyun.starmate.feature.tools.R
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.common.ToolGroup
import org.xmyun.starmate.feature.tools.common.ToolsPrefs
import org.xmyun.starmate.feature.tools.health.HealthAvailability
import org.xmyun.starmate.feature.tools.health.HealthGateway
import org.xmyun.starmate.feature.tools.health.StepBaselineScheduler

/** 首次引导权限邀请页上的一行。授权成功后把该组设为启用 + 让她随时知道。 */
abstract class GroupInvite(
    private val group: ToolGroup,
    private val prefs: ToolsPrefs,
    private val gate: GroupGate,
) : PermissionInvite {

    override suspend fun isGranted(context: Context): Boolean = gate.granted(group)

    protected open fun healthConnectAvailable(): Boolean = false

    protected open fun onGranted() = Unit

    @Composable
    override fun rememberRequester(onResult: (Boolean) -> Unit): () -> Unit {
        val scope = rememberCoroutineScope()
        val hcAvailable = remember { healthConnectAvailable() }
        return rememberGroupPermissionRequester(group, hcAvailable) {
            scope.launch {
                val granted = gate.granted(group)
                if (granted) {
                    prefs.enableWithAmbient(group)
                    onGranted()
                }
                onResult(granted)
            }
        }
    }
}

class LocationInvite @Inject constructor(prefs: ToolsPrefs, gate: GroupGate) :
    GroupInvite(ToolGroup.LOCATION, prefs, gate) {
    override val order = 20
    override val title = R.string.tools_invite_location_title
    override val description = R.string.tools_invite_location_desc
    override val icon: ImageVector = Icons.Outlined.WbSunny
}

class CalendarInvite @Inject constructor(prefs: ToolsPrefs, gate: GroupGate) :
    GroupInvite(ToolGroup.CALENDAR, prefs, gate) {
    override val order = 30
    override val title = R.string.tools_invite_calendar_title
    override val description = R.string.tools_invite_calendar_desc
    override val icon: ImageVector = Icons.Outlined.CalendarMonth
}

class HealthInvite @Inject constructor(
    prefs: ToolsPrefs,
    gate: GroupGate,
    private val health: HealthGateway,
    private val scheduler: StepBaselineScheduler,
) : GroupInvite(ToolGroup.HEALTH, prefs, gate) {
    override val order = 40
    override val title = R.string.tools_invite_health_title
    override val description = R.string.tools_invite_health_desc
    override val icon: ImageVector = Icons.Outlined.FavoriteBorder

    override fun healthConnectAvailable(): Boolean = health.availability() == HealthAvailability.AVAILABLE

    override fun onGranted() = scheduler.schedule()
}

class MusicInvite @Inject constructor(prefs: ToolsPrefs, gate: GroupGate) :
    GroupInvite(ToolGroup.MUSIC, prefs, gate) {
    override val order = 50
    override val title = R.string.tools_invite_music_title
    override val description = R.string.tools_invite_music_desc
    override val icon: ImageVector = Icons.Outlined.LibraryMusic
}
