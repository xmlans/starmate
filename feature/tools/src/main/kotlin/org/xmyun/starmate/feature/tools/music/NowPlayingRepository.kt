package org.xmyun.starmate.feature.tools.music

import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.xmyun.starmate.feature.tools.db.TrackDao
import org.xmyun.starmate.feature.tools.db.TrackEntity

/** 媒体会话里能拿到的曲目信息。只有这几项。 */
data class NowPlaying(
    val title: String,
    val artist: String?,
    val app: String?,
    val playing: Boolean,
)

data class TrackRecord(val title: String, val artist: String?, val app: String?, val at: Instant)

/**
 * 当前曲目（内存）和最近 50 首（tools.db）。
 * 由 MediaListenerService 在媒体会话变化时调用 [update]；播放中且与上一条记录不同才记一条。
 */
@Singleton
class NowPlayingRepository @Inject constructor(
    private val dao: TrackDao,
    private val clock: Clock,
) {
    private val state = MutableStateFlow<NowPlaying?>(null)
    val current: StateFlow<NowPlaying?> = state.asStateFlow()

    private val listening = MutableStateFlow(false)

    /** 通知监听服务是否已连接。 */
    val listenerConnected: StateFlow<Boolean> = listening.asStateFlow()

    private val writeLock = Mutex()

    fun setListenerConnected(connected: Boolean) {
        listening.value = connected
        if (!connected) state.value = null
    }

    suspend fun update(nowPlaying: NowPlaying?) {
        val cleaned = nowPlaying?.let {
            val title = it.title.trim()
            if (title.isEmpty()) null else it.copy(title = title, artist = it.artist?.trim()?.ifEmpty { null })
        }
        state.value = cleaned
        if (cleaned == null || !cleaned.playing) return
        writeLock.withLock {
            val last = dao.last()
            if (last != null && last.title == cleaned.title && last.artist == cleaned.artist && last.app == cleaned.app) return
            dao.insert(TrackEntity(title = cleaned.title, artist = cleaned.artist, app = cleaned.app, atMillis = clock.instant().toEpochMilli()))
            dao.trimTo(MAX_HISTORY)
        }
    }

    suspend fun recent(count: Int): List<TrackRecord> =
        dao.recent(count.coerceIn(1, MAX_HISTORY)).map { TrackRecord(it.title, it.artist, it.app, Instant.ofEpochMilli(it.atMillis)) }

    companion object {
        const val MAX_HISTORY = 50
    }
}
