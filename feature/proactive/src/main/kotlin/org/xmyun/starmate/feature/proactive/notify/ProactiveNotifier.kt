package org.xmyun.starmate.feature.proactive.notify

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmyun.starmate.core.model.AppState
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.CompanionStore
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.previewText
import org.xmyun.starmate.core.ui.contract.AppNavigator
import org.xmyun.starmate.core.ui.contract.StarmateRoutes
import org.xmyun.starmate.feature.proactive.R

/** 通知。真机才能验证，主流程只依赖这个接口。 */
interface ProactiveNotifier {
    /** 通知权限已给且没被用户关掉。 */
    fun canNotify(): Boolean

    suspend fun showMessage(message: ChatMessage)

    /** 取消本模块消息渠道里的全部通知。 */
    fun cancelAll()
}

object ProactiveChannels {
    const val MESSAGES = "proactive_messages"
    const val KEEPALIVE = "proactive_keepalive"

    fun ensure(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(MESSAGES, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.proactive_channel_messages))
                .setDescription(context.getString(R.string.proactive_channel_messages_description))
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(KEEPALIVE, NotificationManagerCompat.IMPORTANCE_MIN)
                .setName(context.getString(R.string.proactive_channel_keepalive))
                .setDescription(context.getString(R.string.proactive_channel_keepalive_description))
                .setShowBadge(false)
                .build(),
        )
    }
}

@Singleton
class AndroidProactiveNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val companions: CompanionStore,
    private val navigator: AppNavigator,
    private val appState: AppState,
    private val diag: DiagLog,
) : ProactiveNotifier {

    override fun canNotify(): Boolean {
        if (!NotificationPermission.isGranted(context)) return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannelCompat(ProactiveChannels.MESSAGES)
        return channel == null || channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }

    @SuppressLint("MissingPermission")
    override suspend fun showMessage(message: ChatMessage) {
        // 用户正在用 App，消息已经在聊天里了
        if (appState.isForeground.value) return
        if (!canNotify()) {
            diag.log(TAG, "notification skipped: not permitted")
            return
        }
        ProactiveChannels.ensure(context)
        val profile = companions.current()
        val icon = withContext(Dispatchers.IO) { loadAvatar(profile.avatarUri) }
        val her = Person.Builder()
            .setName(profile.name)
            .setKey("companion-${profile.id}")
            .apply { if (icon != null) setIcon(icon) }
            .build()
        val me = Person.Builder()
            .setName(profile.userNickname.ifBlank { context.getString(R.string.proactive_notification_me) })
            .build()
        val style = NotificationCompat.MessagingStyle(me)
            .addMessage(message.previewText(), message.createdAt.toEpochMilli(), her)
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_CHAT,
            navigator.intentFor(StarmateRoutes.CHAT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, ProactiveChannels.MESSAGES)
            .setSmallIcon(R.drawable.proactive_ic_notification)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setWhen(message.createdAt.toEpochMilli())
            .setShowWhen(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, message.id.toInt(), notification)
        } catch (e: SecurityException) {
            diag.log(TAG, "notification failed: permission revoked")
        }
    }

    override fun cancelAll() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.activeNotifications
                .filter { it.notification.channelId == ProactiveChannels.MESSAGES }
                .forEach { manager.cancel(it.tag, it.id) }
        } catch (e: Exception) {
            diag.log(TAG, "cancel notifications failed: ${e.javaClass.simpleName}")
        }
    }

    // 头像可能是 content://、file:// 或本地路径；读失败就不带头像
    private fun loadAvatar(uri: String?): IconCompat? {
        if (uri.isNullOrBlank()) return null
        return try {
            // 先只读尺寸（这一步 decodeStream 总是返回 null），再按尺寸降采样解码
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val stream = open(uri) ?: return null
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= AVATAR_PX && bounds.outHeight / (sample * 2) >= AVATAR_PX) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = open(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            IconCompat.createWithBitmap(circleCrop(bitmap))
        } catch (e: Exception) {
            null
        }
    }

    private fun open(uri: String): InputStream? {
        val parsed = Uri.parse(uri)
        return when (parsed.scheme) {
            null, "" -> File(uri).takeIf { it.isFile }?.inputStream()
            "content", "file", "android.resource" -> context.contentResolver.openInputStream(parsed)
            else -> null
        }
    }

    private fun circleCrop(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val output = Bitmap.createBitmap(AVATAR_PX, AVATAR_PX, Bitmap.Config.ARGB_8888)
        val scale = AVATAR_PX.toFloat() / size
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            val matrix = android.graphics.Matrix()
            matrix.setScale(scale, scale)
            matrix.postTranslate(-(source.width - size) / 2f * scale, -(source.height - size) / 2f * scale)
            setLocalMatrix(matrix)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        Canvas(output).drawCircle(AVATAR_PX / 2f, AVATAR_PX / 2f, AVATAR_PX / 2f, paint)
        return output
    }

    private companion object {
        const val TAG = "proactive"
        const val NOTIFICATION_TAG = "proactive_message"
        const val REQUEST_OPEN_CHAT = 0x5702
        const val AVATAR_PX = 192
    }
}
