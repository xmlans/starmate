package org.xmyun.starmate.core.ui.contract

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.ChatMessage

/** 渲染一种附件。type 取自 AttachmentTypes 或模块自定义的字符串。 */
interface AttachmentRenderer {
    val type: String

    @Composable
    fun Render(attachment: Attachment, message: ChatMessage, modifier: Modifier)
}

/** 聊天输入栏上的额外按钮，例如麦克风。 */
interface ChatInputAction {
    val order: Int

    /** insertText 把文字填进输入框，不直接发送。 */
    @Composable
    fun Button(insertText: (String) -> Unit)
}

data class BottomTab(
    val route: String,
    val order: Int,
    @StringRes val label: Int,
    val icon: ImageVector,
)

enum class SettingsGroup { COMPANION, MODELS, ABILITIES, DATA, LAB }

data class SettingsItem(
    val route: String,
    val group: SettingsGroup,
    val order: Int,
    @StringRes val title: Int,
    val icon: ImageVector,
)

/** 一个功能模块对外暴露的页面入口。 */
interface FeatureEntry {
    /** 底部标签。固定四个：聊天 0、日记 1、记忆 2、设置 3。 */
    val tabs: List<BottomTab> get() = emptyList()

    /** 出现在设置页里的条目。 */
    val settingsItems: List<SettingsItem> get() = emptyList()

    /** 注册本模块的全部导航目的地。 */
    fun NavGraphBuilder.routes(nav: NavController) {}
}

/** 首次引导的权限邀请页上的一行。 */
interface PermissionInvite {
    val order: Int

    @get:StringRes
    val title: Int

    @get:StringRes
    val description: Int
    val icon: ImageVector

    suspend fun isGranted(context: Context): Boolean

    /** 返回一个触发授权流程的函数；流程结束后回调 onResult。 */
    @Composable
    fun rememberRequester(onResult: (Boolean) -> Unit): () -> Unit
}

object StarmateRoutes {
    const val CHAT = "chat"
    const val DIARY = "diary"
    const val MEMORY = "memory"
    const val SETTINGS = "settings"
}

/** 由 :app 实现。功能模块发通知时用它得到能打开指定页面的 Intent。 */
interface AppNavigator {
    fun intentFor(route: String): Intent
}
