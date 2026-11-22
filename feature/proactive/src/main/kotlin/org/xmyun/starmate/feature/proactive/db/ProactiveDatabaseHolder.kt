package org.xmyun.starmate.feature.proactive.db

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 持有可替换的 proactive.db 实例（导入时关库、换文件、重开），写法同 ChatDatabaseHolder。 */
class ProactiveDatabaseHolder(
    val dbFile: File,
    private val open: (File) -> ProactiveDatabase,
) {
    private val state = MutableStateFlow<ProactiveDatabase?>(openDb())
    private val swapLock = Mutex()

    /** 正在替换文件时为 null。 */
    val database: StateFlow<ProactiveDatabase?> = state.asStateFlow()

    suspend fun get(): ProactiveDatabase = state.filterNotNull().first()

    suspend fun dao(): ProactiveDao = get().dao()

    suspend fun <T> withOpen(block: suspend (ProactiveDatabase) -> T): T = swapLock.withLock { block(get()) }

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

    private fun openDb(): ProactiveDatabase {
        dbFile.parentFile?.mkdirs()
        return open(dbFile)
    }

    /** 仅测试用。 */
    fun close() {
        state.value?.close()
    }
}
