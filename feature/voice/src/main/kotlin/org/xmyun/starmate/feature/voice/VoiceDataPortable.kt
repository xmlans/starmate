package org.xmyun.starmate.feature.voice

import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmyun.starmate.core.model.DataPortable
import org.xmyun.starmate.feature.voice.cache.VoiceCache

/** 导出导入 files/voice/ 下的音频。导入时整体替换；失败时保留原来的文件。 */
class VoiceDataPortable @Inject constructor(
    private val cache: VoiceCache,
) : DataPortable {

    override val id: String = "voice"

    override suspend fun exportTo(dir: File) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        for (file in cache.audioFiles()) {
            file.copyTo(File(dir, file.name), overwrite = true)
        }
    }

    override suspend fun importFrom(dir: File) = withContext(Dispatchers.IO) {
        if (!dir.isDirectory) return@withContext
        val target = cache.dir
        val parent = target.absoluteFile.parentFile ?: return@withContext
        val staging = File(parent, "${target.name}.importing")
        val backup = File(parent, "${target.name}.backup")
        staging.deleteRecursively()
        backup.deleteRecursively()
        try {
            staging.mkdirs()
            dir.listFiles()
                ?.filter { it.isFile && VoiceCache.isAudioName(it.name) }
                ?.forEach { it.copyTo(File(staging, it.name), overwrite = true) }
            if (target.exists() && !target.renameTo(backup)) {
                throw IllegalStateException("could not move current voice files aside")
            }
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IllegalStateException("could not install imported voice files")
            }
            backup.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
        Unit
    }
}
