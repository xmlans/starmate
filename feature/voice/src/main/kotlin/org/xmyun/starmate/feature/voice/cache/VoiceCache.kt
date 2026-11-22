package org.xmyun.starmate.feature.voice.cache

import android.content.Context
import android.media.MediaMetadataRetriever
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音文件缓存：files/voice/<id>.<格式>。合成时先写隐藏的临时文件，成功后改名，目录里不会出现半截文件。
 * 试听用的音频放在 cacheDir 里，不进导出、不算占用。
 */
@Singleton
class VoiceCache(val dir: File, private val sampleDir: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        File(context.filesDir, DIR_NAME),
        File(context.cacheDir, "voice_sample"),
    )

    fun fileFor(id: String, format: String): File = File(dir, "$id.$format")

    fun tempFileFor(id: String, format: String): File = File(dir, ".$id.$format.tmp")

    /** 这个 id 已有的音频文件（任意格式、非空）。 */
    fun find(id: String): File? {
        if (!isSafeName(id)) return null
        val prefix = "$id."
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith(prefix) && isAudioName(it.name) && it.length() > 0 }
    }

    /** 按附件里记录的文件名取文件；名字不合法或文件不存在时返回 null。 */
    fun resolve(name: String?): File? {
        if (name == null || !isAudioName(name)) return null
        return File(dir, name).takeIf { it.isFile && it.length() > 0 }
    }

    fun ensureDir() {
        dir.mkdirs()
    }

    fun sizeBytes(): Long = audioFiles().sumOf { it.length() }

    fun audioFiles(): List<File> = dir.listFiles()?.filter { it.isFile && isAudioName(it.name) }.orEmpty()

    /** 删除修改时间早于 cutoff 的音频，返回删掉的文件名。顺便清掉残留的临时文件。 */
    fun deleteOlderThan(cutoff: Instant): Set<String> {
        val deleted = mutableSetOf<String>()
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.name.endsWith(".tmp")) {
                file.delete()
            } else if (isAudioName(file.name) && file.lastModified() < cutoff.toEpochMilli()) {
                if (file.delete()) deleted += file.name
            }
        }
        return deleted
    }

    fun sampleFile(format: String): File {
        sampleDir.mkdirs()
        sampleDir.listFiles()?.forEach { it.delete() }
        return File(sampleDir, "sample-${System.nanoTime()}.$format")
    }

    companion object {
        const val DIR_NAME = "voice"
        private val SAFE = Regex("[A-Za-z0-9_-]+")
        private val AUDIO = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9]{1,5}")

        fun isSafeName(id: String): Boolean = SAFE.matches(id)

        /** 只接受 “id.扩展名” 形式的文件名，不含路径分隔符，不以点开头。 */
        fun isAudioName(name: String): Boolean = AUDIO.matches(name)
    }
}

/** 读取音频时长。真机用 MediaMetadataRetriever，测试换成假的。 */
interface AudioDurationProbe {
    fun durationMs(file: File): Long?
}

class MediaDurationProbe @Inject constructor() : AudioDurationProbe {
    override fun durationMs(file: File): Long? = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    } catch (e: Exception) {
        null
    }
}
