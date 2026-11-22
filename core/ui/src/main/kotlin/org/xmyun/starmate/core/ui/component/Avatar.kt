package org.xmyun.starmate.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.xmyun.starmate.core.ui.theme.DisplayFontFamily
import org.xmyun.starmate.core.ui.theme.StarmateTheme

/**
 * 圆形头像。uri 有值时用 Coil 加载（content://、file:// 或本地路径），裁成圆形；
 * 没有 uri、加载中或加载失败时显示名字首字，底色是主题色到邻近色的渐变。
 *
 * 首字大小随头像尺寸缩放，不随系统字体放大（圆圈大小是固定的）。
 *
 * @param onClick 传入时头像可点，点击区域至少 48dp（头像小于 48dp 时四周自动留出触控边距）。
 * @param contentDescription 头像可点或承载信息时传入，例如“她的头像”；纯装饰时保持 null。
 */
@Composable
fun Avatar(
    uri: String?,
    name: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
) {
    var failed by remember(uri) { mutableStateOf(false) }
    val showImage = !uri.isNullOrBlank() && !failed
    val initialSize = with(LocalDensity.current) { (size * 0.5f).toSp() }

    val interactive = if (onClick != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(role = Role.Image, onClick = onClick)
    } else {
        Modifier
    }
    val described = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(interactive)
            .then(described)
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = avatarInitial(name),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontSize = initialSize,
            lineHeight = initialSize,
            fontFamily = DisplayFontFamily,
            maxLines = 1,
        )
        if (showImage) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
            )
        }
    }
}

/** 名字的第一个字（按码点取，emoji 不会被劈开）；拉丁字母转大写；空名字返回“?”。 */
internal fun avatarInitial(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    val first = String(Character.toChars(trimmed.codePointAt(0)))
    return first.uppercase()
}

@PreviewLightDark
@Composable
private fun AvatarPreview() {
    StarmateTheme {
        Surface {
            Row(Modifier.padding(16.dp)) {
                Avatar(uri = null, name = "小星", size = 56.dp)
                Avatar(uri = null, name = "alice", modifier = Modifier.padding(start = 12.dp))
                Avatar(
                    uri = "file:///not/exist.png",
                    name = "Mia",
                    size = 32.dp,
                    modifier = Modifier.padding(start = 12.dp),
                    onClick = {},
                    contentDescription = "Mia",
                )
            }
        }
    }
}
