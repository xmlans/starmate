package org.xmyun.starmate.feature.tools.music

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.xmyun.starmate.feature.tools.common.GroupGate
import org.xmyun.starmate.feature.tools.common.ToolGroup

/**
 * 通知使用权只用来取活跃的媒体会话（MediaSessionManager 要求调用方是已启用的通知监听服务）。
 * 不覆写 onNotificationPosted，不读取任何通知内容。
 */
@AndroidEntryPoint
class MediaListenerService : NotificationListenerService() {

    @Inject
    lateinit var repository: NowPlayingRepository

    @Inject
    lateinit var gate: GroupGate

    private val handler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var sessionManager: MediaSessionManager? = null
    private val controllers = mutableListOf<MediaController>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        attach(list.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = publish()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        repository.setListenerConnected(true)
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        sessionManager = manager
        val component = ComponentName(this, MediaListenerService::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            attach(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            // 通知使用权刚被撤销
            repository.setListenerConnected(false)
        }
    }

    override fun onListenerDisconnected() {
        detach()
        repository.setListenerConnected(false)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        detach()
        repository.setListenerConnected(false)
        super.onDestroy()
    }

    private fun detach() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        sessionManager = null
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers.clear()
        scope?.cancel()
        scope = null
    }

    private fun attach(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers.clear()
        list.forEach {
            it.registerCallback(controllerCallback, handler)
            controllers += it
        }
        publish()
    }

    private fun publish() {
        // 优先正在播放的会话，否则取第一个有曲目信息的
        val chosen = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
            ?: controllers.firstOrNull { it.metadata != null }
        val nowPlaying = chosen?.let { controller ->
            val metadata = controller.metadata ?: return@let null
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: return@let null
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            NowPlaying(
                title = title,
                artist = artist,
                app = appLabel(controller.packageName),
                playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
            )
        }
        // 音乐组关闭时不记录
        scope?.launch { repository.update(if (gate.usable(ToolGroup.MUSIC)) nowPlaying else null) }
    }

    private fun appLabel(packageName: String): String? = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    companion object {
        /** 星伴的通知监听服务是否已被用户启用。 */
        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
