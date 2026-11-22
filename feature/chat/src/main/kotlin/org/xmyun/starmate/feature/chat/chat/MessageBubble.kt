package org.xmyun.starmate.feature.chat.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import org.xmyun.starmate.core.ui.theme.StarmateRadius
import org.xmyun.starmate.core.ui.theme.softShadow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.ui.component.Avatar
import org.xmyun.starmate.core.ui.contract.AttachmentRenderer
import org.xmyun.starmate.core.ui.theme.IncomingBubbleShape
import org.xmyun.starmate.core.ui.theme.OutgoingBubbleShape
import org.xmyun.starmate.core.ui.theme.incomingBubble
import org.xmyun.starmate.core.ui.theme.onIncomingBubble
import org.xmyun.starmate.core.ui.theme.onOutgoingBubble
import org.xmyun.starmate.core.ui.theme.outgoingBubble
import org.xmyun.starmate.feature.chat.R
import org.xmyun.starmate.feature.chat.common.ErrorTexts

private val AvatarSize = 34.dp
private val AvatarGap = 8.dp

/**
 * 气泡的投影和按压范围：一层很浅的带主题色的影子把气泡从壁纸上托起来，
 * 随后按气泡形状裁剪，长按的水波纹就不会溢出圆角。
 */
@Composable
private fun Modifier.bubbleShadow(shape: Shape): Modifier {
    val shadow = MaterialTheme.colorScheme.softShadow
    return this
        .shadow(1.5.dp, shape, clip = false, ambientColor = shadow, spotColor = shadow)
        .clip(shape)
}

/**
 * 一条消息。她的消息靠左、带头像；用户的消息靠右。
 * showAvatar 为 false 时（连续几条她的消息）只留出头像的位置，让气泡对齐。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    item: MessageItem,
    companionName: String,
    companionAvatar: String?,
    showAvatar: Boolean,
    renderers: Map<String, AttachmentRenderer>,
    onCopy: (String) -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onFix: ((String) -> Unit),
    modifier: Modifier = Modifier,
    /** 不为 null 时长按菜单里多一项“举报”。只给她的回复传，且只在有举报入口的版本里传。 */
    onReport: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.78f).dp
    val rendered = item.message.attachments.filter { it.type in renderers }
    val copyLabel = stringResource(R.string.chat_action_copy)

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = if (item.isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!item.isUser) {
            if (showAvatar) {
                Avatar(uri = companionAvatar, name = companionName, size = AvatarSize)
            } else {
                Spacer(Modifier.width(AvatarSize))
            }
            Spacer(Modifier.width(AvatarGap))
        }
        Column(
            horizontalAlignment = if (item.isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = maxBubble),
        ) {
            rendered.forEach { attachment ->
                AttachmentSlot(attachment = attachment, item = item, renderer = renderers.getValue(attachment.type))
            }
            if (item.text.isNotBlank()) {
                Box {
                    val shape = if (item.isUser) OutgoingBubbleShape else IncomingBubbleShape
                    Surface(
                        shape = shape,
                        color = if (item.isUser) colors.outgoingBubble else colors.incomingBubble,
                        contentColor = if (item.isUser) colors.onOutgoingBubble else colors.onIncomingBubble,
                        modifier = Modifier
                            .bubbleShadow(shape)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { menuOpen = true },
                                onLongClickLabel = stringResource(R.string.chat_action_more),
                            ),
                    ) {
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(copyLabel) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onCopy(item.text)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_action_delete)) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                        if (onReport != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_action_report)) },
                                leadingIcon = { Icon(Icons.Outlined.Flag, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onReport()
                                },
                            )
                        }
                    }
                }
            } else if (item.failed) {
                // 失败且一个字都没生成时，也要有个可以长按删除的地方
                Box {
                    Surface(
                        shape = IncomingBubbleShape,
                        color = colors.incomingBubble,
                        modifier = Modifier
                            .bubbleShadow(IncomingBubbleShape)
                            .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
                    ) {
                        Text(
                            text = "…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_action_delete)) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            if (item.tools.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    item.tools.forEach { ToolChip(it) }
                }
            }
            if (item.failed) {
                FailureNote(kind = item.errorKind, onRetry = onRetry, onFix = onFix)
            }
        }
    }
}

@Composable
private fun AttachmentSlot(attachment: Attachment, item: MessageItem, renderer: AttachmentRenderer) {
    Box(Modifier.padding(bottom = if (item.text.isNotBlank()) 6.dp else 0.dp)) {
        renderer.Render(attachment = attachment, message = item.message, modifier = Modifier)
    }
}

@Composable
private fun FailureNote(kind: LlmErrorKind?, onRetry: () -> Unit, onFix: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fixRoute = ErrorTexts.fixRoute(kind)
    // 垫一块半透明的底，说明文字和按钮才不会直接压在壁纸上看不清
    Column(
        Modifier
            .padding(top = 6.dp)
            .background(colors.surface.copy(alpha = 0.82f), RoundedCornerShape(StarmateRadius.Small))
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(ErrorTexts.message(kind)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Row {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.chat_retry)) }
            if (fixRoute != null) {
                TextButton(onClick = { onFix(fixRoute) }) { Text(stringResource(R.string.chat_error_open_models)) }
            }
        }
    }
}

/** 她正在输入：一个安静的小气泡。 */
@Composable
fun TypingRow(companionName: String, companionAvatar: String?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Avatar(uri = companionAvatar, name = companionName, size = AvatarSize)
        Spacer(Modifier.width(AvatarGap))
        Surface(
            shape = IncomingBubbleShape,
            color = colors.incomingBubble,
            modifier = Modifier.bubbleShadow(IncomingBubbleShape),
        ) {
            Text(
                text = stringResource(R.string.chat_typing),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}
