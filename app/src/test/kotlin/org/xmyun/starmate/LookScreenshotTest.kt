package org.xmyun.starmate

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.ui.component.FloatingNavBar
import org.xmyun.starmate.core.ui.component.SettingsChevron
import org.xmyun.starmate.core.ui.component.SettingsDivider
import org.xmyun.starmate.core.ui.component.SettingsRow
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.core.ui.component.SettingsSwitchRow
import org.xmyun.starmate.core.ui.contract.BottomTab
import org.xmyun.starmate.core.ui.contract.ChatInputAction
import org.xmyun.starmate.core.ui.glass.LocalBottomBarInset
import org.xmyun.starmate.core.ui.glass.glassSource
import org.xmyun.starmate.core.ui.glass.rememberGlassState
import org.xmyun.starmate.core.ui.theme.CardShape
import org.xmyun.starmate.core.ui.theme.StarmateTheme
import org.xmyun.starmate.core.ui.theme.ThemeAccent
import org.xmyun.starmate.feature.chat.chat.ChatContent
import org.xmyun.starmate.feature.chat.chat.MessageItem
import org.xmyun.starmate.feature.chat.chat.ToolActivity
import org.xmyun.starmate.feature.chat.onboarding.OnboardingPage

/**
 * 把几个主要页面渲染成图片，存到 app/build/outputs/roborazzi，用来人工检查外观。
 * 不比对像素，只保证这些页面在各主题下画得出来。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
class LookScreenshotTest {

    @Test
    fun chat_pink_light() = shot("chat-pink-light") { Frame(ThemeAccent.PINK, dark = false, CHAT) { SampleChat() } }

    @Test
    fun chat_pink_dark() = shot("chat-pink-dark") { Frame(ThemeAccent.PINK, dark = true, CHAT) { SampleChat() } }

    @Test
    fun chat_blue_light() = shot("chat-blue-light") { Frame(ThemeAccent.BLUE, dark = false, CHAT) { SampleChat() } }

    @Test
    fun settings_pink_light() = shot("settings-pink-light") { Frame(ThemeAccent.PINK, dark = false, SETTINGS) { SampleSettings() } }

    @Test
    fun settings_purple_dark() = shot("settings-purple-dark") { Frame(ThemeAccent.PURPLE, dark = true, SETTINGS) { SampleSettings() } }

    @Test
    fun welcome_pink_light() = shot("welcome-pink-light") {
        StarmateTheme(darkTheme = false, accent = ThemeAccent.PINK) { SampleWelcome() }
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/outputs/roborazzi/$name.png", content = content)
    }
}

private const val CHAT = "chat"
private const val SETTINGS = "settings"

private val TABS = listOf(
    BottomTab(CHAT, 0, org.xmyun.starmate.feature.chat.R.string.chat_tab_chat, Icons.Outlined.ChatBubbleOutline),
    BottomTab("diary", 1, org.xmyun.starmate.feature.diary.R.string.diary_tab, Icons.Outlined.AutoStories),
    BottomTab("memory", 2, org.xmyun.starmate.feature.memory.R.string.memory_tab, Icons.Outlined.Psychology),
    BottomTab(SETTINGS, 3, org.xmyun.starmate.feature.chat.R.string.chat_tab_settings, Icons.Outlined.Settings),
)

/** 和 MainScaffold 一样的摆法：页面铺满，悬浮标签栏盖在上面。 */
@Composable
private fun Frame(accent: ThemeAccent, dark: Boolean, selected: String, page: @Composable () -> Unit) {
    StarmateTheme(darkTheme = dark, accent = accent) {
        val navGlass = rememberGlassState()
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalBottomBarInset provides 80.dp) {
                Box(Modifier.fillMaxSize().glassSource(navGlass)) { page() }
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                FloatingNavBar(state = navGlass, tabs = TABS, selectedRoute = selected, onSelect = {})
            }
        }
    }
}

private val BASE: Instant = Instant.parse("2026-11-12T12:30:00Z")

private fun message(id: Long, user: Boolean, text: String, minutes: Long, tools: List<ToolActivity> = emptyList()) = MessageItem(
    message = ChatMessage(
        id = id,
        role = if (user) Role.USER else Role.ASSISTANT,
        rawText = text,
        displayText = text,
        purpose = TurnPurpose.CHAT,
        createdAt = BASE.plusSeconds(minutes * 60),
    ),
    text = text,
    tools = tools,
    streaming = false,
    failed = false,
    errorKind = null,
)

private val MESSAGES = listOf(
    message(1, false, "下班啦？今天的评审会怎么样，上午你还说有点紧张。", 0),
    message(2, true, "过了！虽然被问了好几个刁钻的问题", 1),
    message(3, true, "现在只想瘫着", 1),
    message(4, false, "那太好了，辛苦啦。刁钻的问题都答上来了，说明你准备得很扎实。", 2),
    message(5, false, "瘫着之前先吃点东西好不好？你中午好像只喝了一杯咖啡。", 2),
    message(6, true, "外面在下雨吗，想出去买个饭", 3),
    message(
        7,
        false,
        "刚查了一下，你那边现在是小雨，18 度，一小时后会停。带把伞，或者等一会儿再出门。",
        4,
        tools = listOf(ToolActivity("t1", "get_weather", "查了天气", running = false, isError = false)),
    ),
    message(8, true, "那我等雨停，先躺二十分钟", 5),
    message(9, false, "好，到点我叫你。", 5),
)

private object MicAction : ChatInputAction {
    override val order = 0

    @Composable
    override fun Button(insertText: (String) -> Unit) {
        IconButton(onClick = {}) { Icon(Icons.Outlined.Mic, contentDescription = null) }
    }
}

@Composable
private fun SampleChat() {
    ChatContent(
        name = "小星",
        avatar = null,
        items = MESSAGES,
        typing = false,
        sending = false,
        input = "",
        draft = null,
        interviewing = false,
        extracting = false,
        confirm = null,
        canLoadMore = false,
        renderers = emptyMap(),
        inputActions = listOf(MicAction),
        snackbar = remember { SnackbarHostState() },
        onLoadMore = {},
        onInputChange = {},
        onInsertText = {},
        onSend = {},
        onClearDraft = {},
        onSkipInterview = {},
        onApprove = { _, _ -> },
        onDeny = {},
        onCopy = {},
        onDelete = {},
        onRetry = {},
        onFix = {},
    )
}

@Composable
private fun SampleSettings() {
    SettingsScaffold(title = "设置", onBack = null) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                SettingsSection(title = "陪伴") {
                    SettingsRow(Icons.Outlined.Favorite, "她的资料", "名字、头像、人设", onClick = {}, trailing = { SettingsChevron() })
                    SettingsDivider()
                    SettingsSwitchRow(Icons.Outlined.Notifications, "主动消息", checked = true, onCheckedChange = {}, summary = "她会偶尔来找你聊天")
                    SettingsDivider()
                    SettingsRow(Icons.Outlined.GraphicEq, "语音", "语音条和语音输入", onClick = {}, trailing = { SettingsChevron() })
                }
            }
            item {
                SettingsSection(title = "模型") {
                    SettingsRow(Icons.Outlined.Tune, "模型与接口", "已配置 2 个", onClick = {}, trailing = { SettingsChevron() })
                    SettingsDivider()
                    SettingsRow(Icons.Outlined.Extension, "工具与连接", "天气、日历、步数等", onClick = {}, trailing = { SettingsChevron() })
                }
            }
            item {
                SettingsSection(title = "数据") {
                    SettingsRow(Icons.Outlined.Palette, "外观", "浅粉", onClick = {}, trailing = { SettingsChevron() })
                    SettingsDivider()
                    SettingsRow(Icons.Outlined.Storage, "备份与恢复", onClick = {}, trailing = { SettingsChevron() })
                    SettingsDivider()
                    SettingsRow(icon = null, title = "版本", trailing = { Text("1.0.0") })
                }
            }
        }
    }
}

@Composable
private fun SampleWelcome() {
    OnboardingPage(
        step = null,
        title = "你好，我是星伴",
        description = "一位只属于你的 AI 伴侣。她记得你说过的话，会主动找你，偶尔写写日记。",
        onBack = null,
        wallpaper = true,
        bottom = {
            Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("开始设置") }
            TextButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("从备份恢复") }
        },
    ) {
        Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
          Column(Modifier.padding(vertical = 8.dp)) {
            listOf(
                Icons.Outlined.Key to "使用你自己的 API key，星伴没有服务器。",
                Icons.Outlined.PhoneAndroid to "聊天记录、记忆、日记都只存在这台手机上。",
                Icons.Outlined.Lock to "只会连接你配置的模型接口和你打开的工具。",
            ).forEach { (icon, text) ->
                SettingsRow(icon = icon, title = text)
            }
          }
        }
    }
}
