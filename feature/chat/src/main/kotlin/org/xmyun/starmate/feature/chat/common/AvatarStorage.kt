package org.xmyun.starmate.feature.chat.common

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 把系统图片选择器给的图片复制进应用私有目录，返回 file:// 地址。旧头像随之删除。 */
@Singleton
class AvatarStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {
    suspend fun import(source: Uri, previous: String?): String? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val target = File(dir, "avatar_${clock.millis()}.img")
        val copied = try {
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output ->
                    var total = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw IllegalArgumentException("avatar too large")
                        output.write(buffer, 0, read)
                    }
                }
                true
            } ?: false
        } catch (e: Exception) {
            target.delete()
            false
        }
        if (!copied) return@withContext null
        deleteOwned(previous)
        Uri.fromFile(target).toString()
    }

    suspend fun discard(uri: String?) = withContext(Dispatchers.IO) { deleteOwned(uri) }

    /** 只删自己目录里的文件。 */
    private fun deleteOwned(uri: String?) {
        val path = uri?.let { Uri.parse(it) }?.takeIf { it.scheme == "file" }?.path ?: return
        val file = File(path)
        val dir = File(context.filesDir, DIR)
        if (file.parentFile?.canonicalPath == dir.canonicalPath) file.delete()
    }

    companion object {
        const val DIR = "chat_avatar"
        private const val MAX_BYTES = 20L * 1024 * 1024
    }
}
