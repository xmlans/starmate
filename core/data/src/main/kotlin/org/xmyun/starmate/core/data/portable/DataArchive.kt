package org.xmyun.starmate.core.data.portable

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.xmyun.starmate.core.model.DataArchiver
import org.xmyun.starmate.core.model.DataPortable

@Serializable
data class ArchiveManifest(
    val formatVersion: Int,
    val appVersion: String,
    val exportedAt: String,
    val modules: List<String> = emptyList(),
)

/**
 * 整体导出导入。zip 根目录放 manifest.json，每个 [DataPortable] 占一个以 id 命名的子目录。
 * 密钥（secrets DataStore）不是 DataPortable，不参与导出。
 */
@Singleton
class DataArchive @Inject constructor(
    @ApplicationContext private val context: Context,
    private val portables: Set<@JvmSuppressWildcards DataPortable>,
    private val clock: Clock,
) : DataArchiver {

    override suspend fun export(target: OutputStream) {
        val ordered = orderedPortables()
        val work = newWorkDir()
        try {
            for (portable in ordered) {
                val dir = File(work, portable.id).apply { mkdirs() }
                portable.exportTo(dir)
            }
            withContext(Dispatchers.IO) {
                val manifest = ArchiveManifest(
                    formatVersion = FORMAT_VERSION,
                    appVersion = appVersion(),
                    exportedAt = clock.instant().toString(),
                    modules = ordered.map { it.id },
                )
                ZipOutputStream(NonClosingOutputStream(target)).use { zip ->
                    zip.putNextEntry(ZipEntry(MANIFEST_NAME))
                    zip.write(json.encodeToString(ArchiveManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    for (portable in ordered) {
                        addTree(zip, File(work, portable.id), portable.id)
                    }
                }
                target.flush()
            }
        } finally {
            withContext(Dispatchers.IO) { work.deleteRecursively() }
        }
    }

    override suspend fun import(source: InputStream) {
        val ordered = orderedPortables()
        val work = newWorkDir()
        try {
            withContext(Dispatchers.IO) {
                try {
                    unzip(source, work)
                } catch (e: ZipException) {
                    throw IllegalArgumentException("archive is not a valid zip", e)
                }
            }
            val manifestFile = File(work, MANIFEST_NAME)
            if (!manifestFile.isFile) throw IllegalArgumentException("archive has no manifest")
            val manifest = try {
                json.decodeFromString(ArchiveManifest.serializer(), manifestFile.readText(Charsets.UTF_8))
            } catch (e: SerializationException) {
                throw IllegalArgumentException("manifest is not valid", e)
            }
            if (manifest.formatVersion != FORMAT_VERSION) {
                throw IllegalArgumentException("unsupported archive format version ${manifest.formatVersion}")
            }
            // 包里没有对应子目录的模块（例如导出时还没装这个模块）不动它的现有数据
            for (portable in ordered) {
                val dir = File(work, portable.id)
                if (dir.isDirectory) portable.importFrom(dir)
            }
        } finally {
            withContext(Dispatchers.IO) { work.deleteRecursively() }
        }
    }

    private fun orderedPortables(): List<DataPortable> {
        val ordered = portables.sortedBy { it.id }
        for (portable in ordered) {
            check(SAFE_ID.matches(portable.id)) { "invalid DataPortable id: ${portable.id}" }
        }
        val duplicates = ordered.groupBy { it.id }.filterValues { it.size > 1 }.keys
        check(duplicates.isEmpty()) { "duplicate DataPortable ids: $duplicates" }
        return ordered
    }

    private fun newWorkDir(): File =
        File(context.cacheDir, "archive-${UUID.randomUUID()}").apply { mkdirs() }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: UNKNOWN_VERSION
    } catch (e: Exception) {
        UNKNOWN_VERSION
    }

    private fun addTree(zip: ZipOutputStream, root: File, prefix: String) {
        zip.putNextEntry(ZipEntry("$prefix/"))
        zip.closeEntry()
        root.walkTopDown()
            .filter { it != root }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { file ->
                val name = "$prefix/${file.relativeTo(root).invariantSeparatorsPath}"
                if (file.isDirectory) {
                    zip.putNextEntry(ZipEntry("$name/"))
                    zip.closeEntry()
                } else {
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
    }

    /** 解压上限，防止异常压缩包写满存储。内部可见，测试里调小。 */
    internal var maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES
    internal var maxEntries: Int = DEFAULT_MAX_ENTRIES

    private fun unzip(source: InputStream, root: File) {
        val canonicalRoot = root.canonicalFile
        var entries = 0
        var totalBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        ZipInputStream(NonClosingInputStream(source)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > maxEntries) throw IllegalArgumentException("archive has too many entries")
                val target = safeTarget(canonicalRoot, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    // 按实际解压出的字节计数，不信任条目头里声明的大小
                    target.outputStream().use { out ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            totalBytes += read
                            if (totalBytes > maxTotalBytes) {
                                throw IllegalArgumentException("archive is too large when extracted")
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val MANIFEST_NAME = "manifest.json"
        private const val UNKNOWN_VERSION = "unknown"
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 4L * 1024 * 1024 * 1024
        const val DEFAULT_MAX_ENTRIES: Int = 100_000
        private val SAFE_ID = Regex("[A-Za-z0-9_-]+")
        private val json = Json { ignoreUnknownKeys = true }

        /** 拒绝绝对路径、盘符、`..` 段，以及解析后跑出解压目录的条目。 */
        internal fun safeTarget(canonicalRoot: File, entryName: String): File {
            val name = entryName.replace('\\', '/')
            if (name.isEmpty() || name.startsWith("/") || (name.length >= 2 && name[1] == ':')) {
                throw IllegalArgumentException("illegal archive entry: $entryName")
            }
            if (name.split('/').any { it == ".." }) {
                throw IllegalArgumentException("illegal archive entry: $entryName")
            }
            val target = File(canonicalRoot, name).canonicalFile
            if (!target.path.startsWith(canonicalRoot.path + File.separator)) {
                throw IllegalArgumentException("illegal archive entry: $entryName")
            }
            return target
        }
    }
}
