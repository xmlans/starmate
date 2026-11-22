package org.xmyun.starmate.feature.voice.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import javax.inject.Inject
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.AttachmentTypes
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.ui.contract.AttachmentRenderer
import org.xmyun.starmate.feature.voice.VoiceAttachments
import org.xmyun.starmate.feature.voice.VoiceState
import org.xmyun.starmate.feature.voice.cache.VoiceCache
import org.xmyun.starmate.feature.voice.play.VoicePlayer
import org.xmyun.starmate.feature.voice.tts.VoiceSynthesizer

class VoiceAttachmentRenderer @Inject constructor(
    private val player: VoicePlayer,
    private val cache: VoiceCache,
    private val synthesizer: VoiceSynthesizer,
) : AttachmentRenderer {

    override val type: String = AttachmentTypes.VOICE

    @Composable
    override fun Render(attachment: Attachment, message: ChatMessage, modifier: Modifier) {
        val payload = remember(attachment) { VoiceAttachments.read(attachment) } ?: return
        val active by synthesizer.activeIds.collectAsState()
        val playback by player.state.collectAsState()
        // 点播放时发现文件已经不在了（被清理或导入时缺失），改为显示文字稿
        var missing by remember(payload.id, payload.file) { mutableStateOf(false) }

        val state = when {
            missing -> VoiceState.FAILED
            // 重启后残留的 recording（合成任务已不在）按失败显示，文字稿不丢
            payload.state == VoiceState.RECORDING -> if (payload.id in active) VoiceState.RECORDING else VoiceState.FAILED
            payload.state == VoiceState.READY && payload.file != null -> VoiceState.READY
            else -> VoiceState.FAILED
        }
        val isCurrent = playback.voiceId == payload.id
        val duration = payload.durationMs ?: playback.durationMs.takeIf { isCurrent && it > 0 }
        val progress = if (isCurrent && playback.durationMs > 0) {
            (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }

        VoiceBubble(
            state = state,
            transcript = payload.transcript,
            durationMs = duration,
            playing = isCurrent && playback.playing,
            progress = progress,
            seed = payload.id.hashCode(),
            onTogglePlay = {
                val file = cache.resolve(payload.file)
                if (file == null) missing = true else player.toggle(payload.id, file)
            },
            modifier = modifier,
        )
    }
}
