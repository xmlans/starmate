package org.xmyun.starmate.core.data.portable

import androidx.room.useWriterConnection
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmyun.starmate.core.data.db.ChatDatabase
import org.xmyun.starmate.core.data.db.ChatDatabaseHolder
import org.xmyun.starmate.core.model.DataPortable
import org.xmyun.starmate.core.model.SqliteImport

/** chat.db 的导出导入。导出前做 WAL checkpoint 再复制；导入时关库、换文件、重开，失败时恢复原文件。 */
@Singleton
class ChatDataPortable @Inject constructor(
    private val holder: ChatDatabaseHolder,
) : DataPortable {

    override val id: String = ID

    override suspend fun exportTo(dir: File) {
        val dest = File(dir, ChatDatabase.FILE_NAME)
        holder.withOpen { db ->
            // 持有写连接期间其他写入会等待，checkpoint 之后主文件就是完整的
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
            source = File(dir, ChatDatabase.FILE_NAME),
            dbFile = holder.dbFile,
            whileClosed = { block -> holder.whileClosed { block() } },
            // Room 校验不通过（版本或结构不符）时会在这里抛异常
            verify = { holder.withOpen { it.chatDao().messageCount() } },
        )
    }

    companion object {
        const val ID = "chat"
    }
}
