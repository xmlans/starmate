package org.xmyun.starmate.feature.tools.music

import java.time.Clock
import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import org.xmyun.starmate.core.model.AmbientSource
import org.xmyun.starmate.core.model.Tool
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.model.ToolResult
import org.xmyun.starmate.core.model.ToolRisk
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.common.JsonArgs.int
import org.xmyun.starmate.feature.tools.common.Schema
import org.xmyun.starmate.feature.tools.common.TimeFmt
import org.xmyun.starmate.feature.tools.common.ToolGroup
import org.xmyun.starmate.feature.tools.common.ToolText
import org.xmyun.starmate.feature.tools.common.error
import org.xmyun.starmate.feature.tools.common.ok
import org.xmyun.starmate.feature.tools.common.pick

internal fun songLabel(title: String, artist: String?, chinese: Boolean): String =
    if (chinese) "《$title》" + (artist?.let { "— $it" } ?: "") else "\"$title\"" + (artist?.let { " by $it" } ?: "")

class MusicTools @Inject constructor(
    private val gate: GroupGate,
    private val repository: NowPlayingRepository,
    private val text: ToolText,
    private val clock: Clock,
) : ToolProvider {

    val nowPlaying: Tool = NowPlayingTool()
    val recent: Tool = Recent()

    override suspend fun tools(purpose: TurnPurpose): List<Tool> =
        if (purpose == TurnPurpose.CHAT && gate.usable(ToolGroup.MUSIC)) listOf(nowPlaying, recent) else emptyList()

    private inner class NowPlayingTool : Tool {
        override val name = "music_now_playing"
        override val description =
            "See what music the user is playing on their phone right now (from media sessions of music apps). " +
                "Use it when the user mentions music or when chatting about their mood or what they are doing. " +
                "Returns song title, artist and app, and whether it is playing or paused."
        override val parametersSchema = Schema.obj()
        override val risk = ToolRisk.READ

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            if (!repository.listenerConnected.value) {
                return text.error("音乐监听服务还没有连上，暂时看不到正在播放的音乐", "The music listener is not connected yet, so playback cannot be seen")
            }
            val now = repository.current.value ?: return text.ok("用户现在没有在播放音乐", "The user is not playing any music right now")
            val song = songLabel(now.title, now.artist, text.chinese)
            val app = now.app?.let { text.pick("（$it）", " ($it)") }.orEmpty()
            return if (now.playing) text.ok("正在播放：$song$app", "Now playing: $song$app") else text.ok("已暂停：$song$app", "Paused: $song$app")
        }
    }

    private inner class Recent : Tool {
        override val name = "music_recent"
        override val description =
            "List songs the user played recently on their phone, newest first. Use it to talk about their taste " +
                "in music or what they listened to today. `count` is 1-20 (default 10). Returns local time (with " +
                "weekday), song title, artist and app."
        override val parametersSchema = Schema.obj(
            "count" to Schema.integer("How many songs, 1-20, default 10", 1, MAX_COUNT),
        )
        override val risk = ToolRisk.READ

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val count = (args.int("count") ?: 10).coerceIn(1, MAX_COUNT)
            val tracks = repository.recent(count)
            if (tracks.isEmpty()) return text.error("还没有听歌记录", "No listening history yet")
            val f = TimeFmt(clock, text.chinese)
            return ToolResult(
                tracks.joinToString("\n") { t ->
                    val app = t.app?.let { text.pick("（$it）", " ($it)") }.orEmpty()
                    "- ${f.dateTime(t.at)} ${songLabel(t.title, t.artist, text.chinese)}$app"
                },
            )
        }
    }

    companion object {
        const val MAX_COUNT = 20
    }
}

/** 正在播放时返回“正在听《歌名》— 歌手”，否则 null。 */
class MusicAmbient @Inject constructor(
    private val gate: GroupGate,
    private val repository: NowPlayingRepository,
    private val text: ToolText,
) : AmbientSource {
    override val id: String = "tools_music"

    override suspend fun snapshot(): String? {
        if (!gate.ambientOn(ToolGroup.MUSIC)) return null
        val now = repository.current.value?.takeIf { it.playing } ?: return null
        return text.pick("正在听", "Listening to ") + songLabel(now.title, now.artist, text.chinese)
    }
}
