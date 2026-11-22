package org.xmyun.starmate.feature.voice.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.xmyun.starmate.core.ui.theme.IncomingBubbleShape
import org.xmyun.starmate.core.ui.theme.StarmateTheme
import org.xmyun.starmate.core.ui.theme.incomingBubble
import org.xmyun.starmate.core.ui.theme.onIncomingBubble
import org.xmyun.starmate.feature.voice.R
import org.xmyun.starmate.feature.voice.VoiceState

/**
 * 语音条，配色和圆角与她的聊天气泡一致。
 * - recording：“正在录音…”和跳动的音量条。
 * - ready：播放按钮、波形进度、时长，可展开文字稿。
 * - failed：直接显示文字稿。
 */
@Composable
fun VoiceBubble(
    state: VoiceState,
    transcript: String,
    durationMs: Long?,
    playing: Boolean,
    progress: Float,
    seed: Int,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = IncomingBubbleShape,
        color = MaterialTheme.colorScheme.incomingBubble,
        contentColor = MaterialTheme.colorScheme.onIncomingBubble,
    ) {
        when (state) {
            VoiceState.RECORDING -> RecordingContent()
            VoiceState.READY -> ReadyContent(transcript, durationMs, playing, progress, seed, onTogglePlay)
            VoiceState.FAILED -> Text(
                text = transcript,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun RecordingContent() {
    val transition = rememberInfiniteTransition(label = "recording")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Restart),
        label = "recordingPhase",
    )
    val barColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.size(width = 22.dp, height = 18.dp)) {
            val bars = 4
            val gap = size.width / (bars * 2 - 1)
            for (i in 0 until bars) {
                val local = ((phase + i * 0.22f) % 1f)
                val level = 0.3f + 0.7f * (if (local < 0.5f) local * 2 else (1 - local) * 2)
                val h = size.height * level
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(i * gap * 2, (size.height - h) / 2),
                    size = Size(gap, h),
                    cornerRadius = CornerRadius(gap / 2, gap / 2),
                )
            }
        }
        Text(text = stringResource(R.string.voice_recording), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReadyContent(
    transcript: String,
    durationMs: Long?,
    playing: Boolean,
    progress: Float,
    seed: Int,
    onTogglePlay: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val seconds = durationMs?.let { ((it + 500) / 1000).toInt().coerceAtLeast(1) }
    val playDescription = if (seconds != null) {
        stringResource(R.string.voice_play_seconds, seconds)
    } else {
        stringResource(R.string.voice_play)
    }
    val pauseDescription = stringResource(R.string.voice_pause)

    Column(modifier = Modifier.widthIn(max = 280.dp)) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onTogglePlay)
                    .semantics { contentDescription = if (playing) pauseDescription else playDescription },
            ) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Waveform(
                progress = if (playing || progress > 0f) progress else 0f,
                seed = seed,
                width = waveformWidth(durationMs),
            )
            if (seconds != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.voice_duration_seconds, seconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = stringResource(if (expanded) R.string.voice_hide_text else R.string.voice_show_text),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 8.dp, bottom = 4.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        AnimatedVisibility(visible = expanded) {
            Column {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text(
                    text = transcript,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 语音越长条越宽，和常见的聊天软件一样。 */
private fun waveformWidth(durationMs: Long?): Dp {
    val seconds = ((durationMs ?: 3000L) / 1000f).coerceIn(1f, 60f)
    return (72 + (seconds / 60f) * 108).roundToInt().dp
}

/** 按 seed 生成固定的波形，已播放的部分用主色。纯装饰，读屏不读。 */
@Composable
private fun Waveform(progress: Float, seed: Int, width: Dp) {
    val played = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val heights = remember(seed) {
        var x = seed.toLong() xor 0x5DEECE66DL
        List(28) {
            x = (x * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1)
            0.25f + ((x ushr 17) % 1000) / 1000f * 0.75f
        }
    }
    Canvas(Modifier.size(width = width, height = 24.dp)) {
        val count = (size.width / 6.dp.toPx()).toInt().coerceIn(8, heights.size)
        val step = size.width / count
        val barWidth = step * 0.55f
        for (i in 0 until count) {
            val h = size.height * heights[i]
            val color: Color = if ((i + 0.5f) / count <= progress) played else rest
            drawRoundRect(
                color = color,
                topLeft = Offset(i * step, (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun VoiceBubblePreview() {
    StarmateTheme {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VoiceBubble(VoiceState.RECORDING, "晚安，早点睡哦。", null, false, 0f, 1, {})
                VoiceBubble(VoiceState.READY, "晚安，早点睡哦。", 4200, true, 0.4f, 2, {})
                VoiceBubble(VoiceState.FAILED, "晚安，早点睡哦。", null, false, 0f, 3, {})
                Spacer(Modifier.height(4.dp).background(Color.Transparent))
            }
        }
    }
}
