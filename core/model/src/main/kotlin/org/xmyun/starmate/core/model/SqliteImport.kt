package org.xmyun.starmate.core.model

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从备份包导入一个 SQLite 数据库文件的公共步骤。聊天、记忆、日记、主动消息四个库都用它，
 * “导入失败时不丢原有数据”的保证只在这一处实现。
 */
object SqliteImport {

    private const val BACKUP_SUFFIX = ".import-bak"
    private val SIDE_FILES = listOf("-wal", "-shm", "-journal")
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * 用 [source] 换掉 [dbFile]。步骤：检查 source 是 SQLite 文件 → 关库，把现有库挪成备份，换入新文件 →
     * 重新打开并 [verify] → 通过就删掉备份；不通过就把备份挪回来，再抛 IllegalArgumentException。
     * 不论成败，结束时库都是打开过且可用的状态（成功是新库，失败是原来的库）。
     *
     * @param name 出错消息里用的名字，例如 "chat"。
     * @param whileClosed 把库关上、执行传给它的代码块、之后允许重新打开。各模块传自己 DatabaseHolder 的 whileClosed。
     * @param verify 打开库并读一下（例如数一下行数）。版本或结构不对时 Room 会在这里抛异常。
     */
    suspend fun replace(
        name: String,
        source: File,
        dbFile: File,
        whileClosed: suspend (block: suspend () -> Unit) -> Unit,
        verify: suspend () -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            require(source.isFile) { "$name database missing in archive" }
            require(hasSqliteHeader(source)) { "$name database in archive is not a SQLite file" }
        }
        val backup = File(dbFile.path + BACKUP_SUFFIX)
        whileClosed {
            withContext(Dispatchers.IO) {
                dbFile.parentFile?.mkdirs()
                backup.delete()
                if (dbFile.exists() && !dbFile.renameTo(backup)) dbFile.copyTo(backup, overwrite = true)
                deleteDbFiles(dbFile)
                source.copyTo(dbFile, overwrite = true)
            }
        }
        val opened = try {
            verify()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!opened) {
            whileClosed {
                withContext(Dispatchers.IO) {
                    deleteDbFiles(dbFile)
                    if (backup.exists() && !backup.renameTo(dbFile)) backup.copyTo(dbFile, overwrite = true)
                }
            }
            withContext(Dispatchers.IO) { backup.delete() }
            throw IllegalArgumentException("$name database in archive cannot be opened")
        }
        withContext(Dispatchers.IO) { backup.delete() }
    }

    fun hasSqliteHeader(file: File): Boolean {
        val header = ByteArray(SQLITE_HEADER.size)
        val read = file.inputStream().use { it.read(header) }
        return read == header.size && header.contentEquals(SQLITE_HEADER)
    }

    /** 删掉库文件和它的 WAL、共享内存、回滚日志，免得旧的日志被套到新库上。 */
    private fun deleteDbFiles(target: File) {
        target.delete()
        for (suffix in SIDE_FILES) File(target.path + suffix).delete()
    }
}
