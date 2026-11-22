package org.xmyun.starmate.core.data.db

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 持有可替换的 chat.db 实例。导入时要关闭数据库、替换文件、重新打开，
 * 所以各 store 不直接持有 [ChatDatabase]，而是每次通过这里取。
 */
class ChatDatabaseHolder(
    val dbFile: File,
    private val open: (File) -> ChatDatabase,
) {
    private val state = MutableStateFlow<ChatDatabase?>(openDb())
    private val swapLock = Mutex()

    /** 正在替换文件时为 null。 */
    val database: StateFlow<ChatDatabase?> = state.asStateFlow()

    /** 替换文件期间挂起等待。 */
    suspend fun get(): ChatDatabase = state.filterNotNull().first()

    suspend fun dao(): ChatDao = get().chatDao()

    /** 持有替换锁执行，保证期间不会被关闭（导出用）。 */
    suspend fun <T> withOpen(block: suspend (ChatDatabase) -> T): T = swapLock.withLock { block(get()) }

    /** 关闭数据库后执行 block（可替换 [dbFile]），结束后无论成败都重新打开。 */
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

    // SQLite 驱动不会创建父目录
    private fun openDb(): ChatDatabase {
        dbFile.parentFile?.mkdirs()
        return open(dbFile)
    }

    /** 仅测试用。 */
    fun close() {
        state.value?.close()
    }
}
