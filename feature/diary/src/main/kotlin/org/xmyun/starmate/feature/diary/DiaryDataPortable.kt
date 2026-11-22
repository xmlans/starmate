package org.xmyun.starmate.feature.diary

import androidx.room.useWriterConnection
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmyun.starmate.core.model.DataPortable
import org.xmyun.starmate.core.model.SqliteImport
import org.xmyun.starmate.feature.diary.db.DiaryDatabase
import org.xmyun.starmate.feature.diary.db.DiaryDatabaseHolder

/** diary.db 的导出导入。导出前做 WAL checkpoint 再复制；导入时关库、换文件、重开，失败时恢复原文件。 */
@Singleton
class DiaryDataPortable @Inject constructor(
    private val holder: DiaryDatabaseHolder,
) : DataPortable {

    override val id: String = ID

    override suspend fun exportTo(dir: File) {
        val dest = File(dir, DiaryDatabase.FILE_NAME)
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
            source = File(dir, DiaryDatabase.FILE_NAME),
            dbFile = holder.dbFile,
            whileClosed = { block -> holder.whileClosed { block() } },
            verify = { holder.withOpen { it.dao().count() } },
        )
    }

    companion object {
        const val ID = "diary"
    }
}
