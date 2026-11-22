package org.xmyun.starmate.feature.proactive

import androidx.room.useWriterConnection
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmyun.starmate.core.model.DataPortable
import org.xmyun.starmate.core.model.SqliteImport
import org.xmyun.starmate.feature.proactive.db.ProactiveDatabase
import org.xmyun.starmate.feature.proactive.db.ProactiveDatabaseHolder
import org.xmyun.starmate.feature.proactive.prefs.ProactivePrefs
import org.xmyun.starmate.feature.proactive.schedule.CareRescheduler

/** proactive.db 和总开关的导出导入。导入后按新数据重新登记闹钟。 */
@Singleton
class ProactiveDataPortable @Inject constructor(
    private val holder: ProactiveDatabaseHolder,
    private val prefs: ProactivePrefs,
    private val rescheduler: CareRescheduler,
) : DataPortable {

    override val id: String = ID

    override suspend fun exportTo(dir: File) {
        val dest = File(dir, ProactiveDatabase.FILE_NAME)
        holder.withOpen { db ->
            db.useWriterConnection { connection ->
                connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { it.step() }
                withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    holder.dbFile.copyTo(dest, overwrite = true)
                }
            }
        }
        val prefsJson = prefs.exportJson()
        withContext(Dispatchers.IO) { File(dir, PREFS_FILE).writeText(prefsJson, Charsets.UTF_8) }
    }

    override suspend fun importFrom(dir: File) {
        // 旧库里约定的闹钟先撤掉，导入后按库里的数据重新登记
        rescheduler.cancelAllAlarms()
        try {
            SqliteImport.replace(
                name = ID,
                source = File(dir, ProactiveDatabase.FILE_NAME),
                dbFile = holder.dbFile,
                whileClosed = { block -> holder.whileClosed { block() } },
                verify = { holder.withOpen { it.dao().careCount() } },
            )
        } catch (e: IllegalArgumentException) {
            // 导入没成：库已恢复原样，把刚才撤掉的闹钟登记回来
            rescheduler.rescheduleAll()
            throw e
        }
        val prefsFile = File(dir, PREFS_FILE)
        val prefsJson = withContext(Dispatchers.IO) { if (prefsFile.isFile) prefsFile.readText(Charsets.UTF_8) else null }
        if (prefsJson != null) prefs.importJson(prefsJson)
        rescheduler.rescheduleAll()
    }

    companion object {
        const val ID = "proactive"
        const val PREFS_FILE = "prefs.json"
    }
}
