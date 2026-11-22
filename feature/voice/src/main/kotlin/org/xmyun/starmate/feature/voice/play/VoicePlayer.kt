package org.xmyun.starmate.feature.voice.play

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 当前播放状态。voiceId 为 null 表示没有在播。 */
data class PlaybackState(
    val voiceId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/** 语音条播放器。同一时间只播一条：播新的会停掉旧的。只能真机验证。 */
interface VoicePlayer {
    val state: StateFlow<PlaybackState>

    /** 没在播这条就从头播；正在播就暂停；暂停中就继续。 */
    fun toggle(voiceId: String, file: File)

    fun stop()
}

@Singleton
class ExoVoicePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoicePlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var ticker: Job? = null

    override fun toggle(voiceId: String, file: File) {
        scope.launch {
            val current = _state.value
            val p = player
            if (p != null && current.voiceId == voiceId) {
                when {
                    p.isPlaying -> p.pause()
                    p.playbackState == Player.STATE_ENDED -> {
                        p.seekTo(0)
                        p.play()
                    }
                    else -> p.play()
                }
                return@launch
            }
            val exo = p ?: build().also { player = it }
            exo.stop()
            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            exo.prepare()
            exo.play()
            _state.value = PlaybackState(voiceId = voiceId, playing = true)
        }
    }

    override fun stop() {
        scope.launch {
            player?.stop()
            reset()
        }
    }

    private fun build(): ExoPlayer {
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        return ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(attributes, true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (_state.value.voiceId == null) return
                    _state.value = _state.value.copy(playing = isPlaying, durationMs = duration.coerceAtLeast(0))
                    if (isPlaying) startTicker() else ticker?.cancel()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) reset()
                }

                override fun onPlayerError(error: PlaybackException) {
                    reset()
                }
            })
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                _state.value = _state.value.copy(
                    positionMs = p.currentPosition.coerceAtLeast(0),
                    durationMs = p.duration.coerceAtLeast(0),
                )
                delay(TICK_MS)
            }
        }
    }

    private fun reset() {
        ticker?.cancel()
        ticker = null
        _state.value = PlaybackState()
    }

    private companion object {
        const val TICK_MS = 100L
    }
}
