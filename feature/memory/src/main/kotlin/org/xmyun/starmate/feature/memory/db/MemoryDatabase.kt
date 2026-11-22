package org.xmyun.starmate.feature.memory.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Database(
    entities = [
        MemoryEntity::class, EpisodeEntity::class, GistEntity::class, LinkEntity::class,
        VectorEntity::class, StateEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun dao(): MemoryDao

    companion object {
        const val FILE_NAME = "memory.db"

        /** name 只传文件名（Windows 宿主上的绝对路径会被 Room 拒绝）。 */
        fun build(context: Context, name: String = FILE_NAME, driver: SQLiteDriver = BundledSQLiteDriver()): MemoryDatabase =
            Room.databaseBuilder(context.applicationContext, MemoryDatabase::class.java, name)
                .setDriver(driver)
                .setQueryCoroutineContext(Dispatchers.IO)
                .addCallback(FtsTables.callback)
                .build()
    }
}

/**
 * Room 的注解不支持 FTS5，这里用原生 SQL 创建全文索引和触发器。
 * episode_fts 的 rowid 等于原话编号；body 是原文，keys 是检索词项（含事实反哺的关键词）。
 */
object FtsTables {
    const val EPISODE_FTS = "episode_fts"

    private val STATEMENTS = listOf(
        "CREATE VIRTUAL TABLE IF NOT EXISTS $EPISODE_FTS USING fts5(body, keys, tokenize='trigram')",
        "INSERT OR IGNORE INTO state (id, digest, consolidatedUpto, consolidations, revision, batchLimit, failEpisode, failCount) " +
            "VALUES (1, '', 0, 0, 0, 0, 0, 0)",
        // 记忆内容变化时递增 revision；只改 lastUsedAt、hits、nudgedAt 不算变化，稳定快照保持逐字不变
        "CREATE TRIGGER IF NOT EXISTS memory_rev_insert AFTER INSERT ON memories BEGIN " +
            "UPDATE state SET revision = revision + 1 WHERE id = 1; END",
        "CREATE TRIGGER IF NOT EXISTS memory_rev_delete AFTER DELETE ON memories BEGIN " +
            "UPDATE state SET revision = revision + 1 WHERE id = 1; END",
        "CREATE TRIGGER IF NOT EXISTS memory_rev_update AFTER UPDATE ON memories WHEN " +
            listOf("content", "category", "importance", "pinned", "archived", "tags", "source", "eventAt", "invalidAt", "emotion")
                .joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" } +
            " BEGIN UPDATE state SET revision = revision + 1 WHERE id = 1; END",
    )

    val callback = object : RoomDatabase.Callback() {
        override fun onOpen(connection: SQLiteConnection) {
            STATEMENTS.forEach { connection.execSQL(it) }
        }
    }
}

/**
 * 持有可替换的 memory.db。导入时要关库、换文件、重开；清空和导入都会让 generation 加一，
 * 正在进行的整理在写回前检查它，避免把清空前的结果写回去。
 */
class MemoryDatabaseHolder(
    val dbFile: File,
    private val open: (File) -> MemoryDatabase,
) {
    private val state = MutableStateFlow<MemoryDatabase?>(openDb())
    private val swapLock = Mutex()
    private val gen = AtomicLong(0)

    val database: StateFlow<MemoryDatabase?> = state.asStateFlow()

    val generation: Long get() = gen.get()

    fun bumpGeneration() {
        gen.incrementAndGet()
    }

    suspend fun get(): MemoryDatabase = state.filterNotNull().first()

    suspend fun dao(): MemoryDao = get().dao()

    suspend fun <T> withOpen(block: suspend (MemoryDatabase) -> T): T = swapLock.withLock { block(get()) }

    suspend fun <T> whileClosed(block: suspend (File) -> T): T = swapLock.withLock {
        val old = get()
        state.value = null
        gen.incrementAndGet()
        try {
            old.close()
            block(dbFile)
        } finally {
            state.value = openDb()
        }
    }

    private fun openDb(): MemoryDatabase {
        dbFile.parentFile?.mkdirs()
        return open(dbFile)
    }

    /** 仅测试用。 */
    fun close() {
        state.value?.close()
    }
}
