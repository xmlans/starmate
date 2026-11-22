package org.xmyun.starmate.feature.diary.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一篇日记。date 是归属日期（ISO，如 2026-11-12），每天最多一篇。 */
@Entity(tableName = "entries", indices = [Index(value = ["date"], unique = true)])
data class DiaryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val title: String,
    val mood: String,
    val body: String,
    val summary: String,
    val createdAt: Long,
    val read: Boolean = false,
)

@Dao
interface DiaryDao {
    @Query("SELECT * FROM entries ORDER BY date DESC")
    fun observeAll(): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM entries WHERE id = :id")
    fun observe(id: Long): Flow<DiaryEntryEntity?>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun byId(id: Long): DiaryEntryEntity?

    @Query("SELECT * FROM entries WHERE date = :date")
    suspend fun byDate(date: String): DiaryEntryEntity?

    @Query("SELECT * FROM entries ORDER BY date DESC LIMIT 1")
    suspend fun latest(): DiaryEntryEntity?

    @Query("SELECT COUNT(*) FROM entries")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entry: DiaryEntryEntity): Long

    @Query("UPDATE entries SET read = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [DiaryEntryEntity::class], version = 1, exportSchema = false)
abstract class DiaryDatabase : RoomDatabase() {
    abstract fun dao(): DiaryDao

    companion object {
        const val FILE_NAME = "diary.db"

        /** name 只传文件名（Windows 宿主上的绝对路径会被 Room 拒绝）。 */
        fun build(context: Context, name: String = FILE_NAME, driver: SQLiteDriver = BundledSQLiteDriver()): DiaryDatabase =
            Room.databaseBuilder(context.applicationContext, DiaryDatabase::class.java, name)
                .setDriver(driver)
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}

/** 持有可替换的 diary.db。导入时关库、换文件、重开。 */
class DiaryDatabaseHolder(
    val dbFile: File,
    private val open: (File) -> DiaryDatabase,
) {
    private val state = MutableStateFlow<DiaryDatabase?>(openDb())
    private val swapLock = Mutex()

    val database: StateFlow<DiaryDatabase?> = state.asStateFlow()

    suspend fun get(): DiaryDatabase = state.filterNotNull().first()

    suspend fun dao(): DiaryDao = get().dao()

    suspend fun <T> withOpen(block: suspend (DiaryDatabase) -> T): T = swapLock.withLock { block(get()) }

    suspend fun <T> whileClosed(block: suspend (File) -> T): T = swapLock.withLock {
        val old = get()
        state.value = null
        try {
            old.close()
            block(dbFile)
        } finally {
            state.value = openDb()
        }
    }

    private fun openDb(): DiaryDatabase {
        dbFile.parentFile?.mkdirs()
        return open(dbFile)
    }

    /** 仅测试用。 */
    fun close() {
        state.value?.close()
    }
}
