package org.xmyun.starmate.feature.memory

import androidx.room.useWriterConnection
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmyun.starmate.core.model.DataPortable
import org.xmyun.starmate.core.model.SqliteImport
import org.xmyun.starmate.feature.memory.db.MemoryDatabase
import org.xmyun.starmate.feature.memory.db.MemoryDatabaseHolder

/** memory.db 的导出导入。导出前做 WAL checkpoint 再复制；导入时关库、换文件、重开，失败时恢复原文件。 */
@Singleton
class MemoryDataPortable @Inject constructor(
    private val holder: MemoryDatabaseHolder,
) : DataPortable {

    override val id: String = ID

    override suspend fun exportTo(dir: File) {
        val dest = File(dir, MemoryDatabase.FILE_NAME)
        holder.withOpen { db ->
            db.useWriterConnection { connection ->
                connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { it.step() }
                withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    holder.dbFile.copyTo(dest, overwrite = true)
                }
            }
        }
    }

    override suspend fun importFrom(dir: File) {
        SqliteImport.replace(
            name = ID,
            source = File(dir, MemoryDatabase.FILE_NAME),
            dbFile = holder.dbFile,
            whileClosed = { block -> holder.whileClosed { block() } },
            verify = { holder.withOpen { it.dao().countEpisodes() } },
        )
    }

    companion object {
        const val ID = "memory"
    }
}
