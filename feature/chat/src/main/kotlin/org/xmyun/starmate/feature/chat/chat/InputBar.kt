package org.xmyun.starmate.feature.chat.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.Role as MessageRole
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.ui.contract.AttachmentRenderer
import org.xmyun.starmate.core.ui.contract.ChatInputAction
import org.xmyun.starmate.core.ui.glass.GlassState
import org.xmyun.starmate.core.ui.glass.glass
import org.xmyun.starmate.core.ui.glass.softShadow
import org.xmyun.starmate.core.ui.theme.CardShape
import org.xmyun.starmate.feature.chat.R

private val FieldMinHeight = 56.dp
private val FieldShape = RoundedCornerShape(28.dp)

/**
 * 输入栏，浮在聊天壁纸上：一条毛玻璃胶囊里放各模块的输入按钮和文字框，旁边是圆形的发送键。
 * 有待发附件时，上面多一张毛玻璃小卡片。
 *
 * @param glass 聊天页骨架给的 [GlassState]，用来把滚到输入框下面的消息糊掉。
 */
@Composable
fun InputBar(
    glass: GlassState,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    sendEnabled: Boolean,
    draft: Attachment?,
    onClearDraft: () -> Unit,
    renderers: Map<String, AttachmentRenderer>,
    actions: List<ChatInputAction>,
    onInsertText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (draft != null) {
            Box(Modifier.fillMaxWidth().glass(glass, shape = CardShape, elevation = 6.dp).padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
                DraftPreview(draft = draft, renderers = renderers, onClear = onClearDraft)
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = FieldMinHeight)
                    .glass(glass, shape = FieldShape, elevation = 6.dp)
                    .padding(start = if (actions.isEmpty()) 6.dp else 4.dp, end = 6.dp),
            ) {
                actions.forEach { action ->
                    // 按钮本身 48dp，垫 4dp 后和单行文字框的中线对齐
                    Box(Modifier.padding(bottom = 4.dp)) { action.Button(insertText = onInsertText) }
                }
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.width(8.dp))
            SendButton(glass = glass, enabled = sendEnabled, onClick = onSend)
        }
    }
}

/** 发送键。能发时是主题色的实心圆；没东西可发时退成一颗毛玻璃圆，不抢眼。 */
@Composable
private fun SendButton(glass: GlassState, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val surface = if (enabled) {
        Modifier
            .softShadow(CircleShape, 6.dp)
            .clip(CircleShape)
            .background(colors.primary)
    } else {
        Modifier.glass(glass, shape = CircleShape, elevation = 6.dp)
    }
    Box(
        modifier = Modifier
            .size(FieldMinHeight)
            .then(surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Send,
            contentDescription = stringResource(R.string.chat_send),
            tint = if (enabled) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun DraftPreview(draft: Attachment, renderers: Map<String, AttachmentRenderer>, onClear: () -> Unit) {
    val renderer = renderers[draft.type]
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (renderer != null) {
                // 渲染器需要一条消息作上下文，这里给一条只带这个附件的草稿消息
                val stub = ChatMessage(
                    role = MessageRole.USER,
                    rawText = "",
                    displayText = "",
                    attachments = listOf(draft),
                    purpose = TurnPurpose.CHAT,
                    createdAt = java.time.Instant.EPOCH,
                )
                renderer.Render(attachment = draft, message = stub, modifier = Modifier)
            } else {
                val content = QuoteContent.of(draft)
                Text(
                    text = content.title ?: content.text ?: stringResource(R.string.chat_draft_attachment),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onClear) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chat_draft_remove))
        }
    }
}
