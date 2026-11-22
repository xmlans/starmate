package org.xmyun.starmate.feature.proactive

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import javax.inject.Inject
import org.xmyun.starmate.core.ui.contract.PermissionInvite
import org.xmyun.starmate.feature.proactive.notify.NotificationPermission
import org.xmyun.starmate.feature.proactive.notify.rememberNotificationPermissionRequester

/** 首次引导里的通知权限。Android 13+ 请求 POST_NOTIFICATIONS，更低版本视为已授权。 */
class NotificationPermissionInvite @Inject constructor() : PermissionInvite {
    override val order: Int = 10
    override val title: Int = R.string.proactive_invite_title
    override val description: Int = R.string.proactive_invite_description
    override val icon: ImageVector = Icons.Outlined.Notifications

    override suspend fun isGranted(context: Context): Boolean = NotificationPermission.isGranted(context)

    @Composable
    override fun rememberRequester(onResult: (Boolean) -> Unit): () -> Unit =
        rememberNotificationPermissionRequester(onResult)
}
