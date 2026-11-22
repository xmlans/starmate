package org.xmyun.starmate.feature.chat.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.xmyun.starmate.core.model.ATTACHMENT_PREVIEW_KEY
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.AttachmentTypes
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.ui.contract.AttachmentRenderer
import org.xmyun.starmate.core.ui.theme.StarmateRadius

/**
 * 引用附件，例如日记页“回复这篇”带过来的一段。
 * payload 读 title（可选）、text（可选），都没有时退回 preview。
 */
class QuoteRenderer @Inject constructor() : AttachmentRenderer {

    override val type: String = AttachmentTypes.QUOTE

    @Composable
    override fun Render(attachment: Attachment, message: ChatMessage, modifier: Modifier) {
        val content = QuoteContent.of(attachment)
        if (content.isEmpty) return
        QuoteBlock(content = content, outgoing = message.role == Role.USER, modifier = modifier)
    }
}

data class QuoteContent(val title: String?, val text: String?) {
    val isEmpty: Boolean get() = title.isNullOrBlank() && text.isNullOrBlank()

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_TEXT = "text"

        fun of(attachment: Attachment): QuoteContent {
            fun field(key: String) = (attachment.payload[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val title = field(KEY_TITLE)
            val text = field(KEY_TEXT) ?: if (title == null) field(ATTACHMENT_PREVIEW_KEY) else null
            return QuoteContent(title, text)
        }
    }
}

@Composable
fun QuoteBlock(content: QuoteContent, outgoing: Boolean, modifier: Modifier = Modifier, maxLines: Int = 4) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(StarmateRadius.Small),
        color = if (outgoing) colors.surfaceContainerLowest.copy(alpha = 0.6f) else colors.surfaceContainerLow,
        modifier = modifier,
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(colors.primary.copy(alpha = 0.6f)),
            )
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                content.title?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                content.text?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
