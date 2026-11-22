package org.xmyun.starmate.core.model

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

interface SecretStore {
    suspend fun get(name: String): String?

    /** value 为 null 表示删除。 */
    suspend fun set(name: String, value: String?)
}

/** 参与数据导出和导入。id 是 zip 里的子目录名。 */
interface DataPortable {
    val id: String
    suspend fun exportTo(dir: File)
    suspend fun importFrom(dir: File)
}

/** 整体导出和导入。由 :core:data 实现，内部依次调用各 DataPortable。 */
interface DataArchiver {
    suspend fun export(target: OutputStream)

    /** 格式不认识或内容不合法时抛 IllegalArgumentException，且不改动现有数据。 */
    suspend fun import(source: InputStream)
}

data class DiagEntry(val at: Instant, val tag: String, val message: String)

/** 诊断日志。实现负责把 key、Bearer 令牌、URL 里的 key 参数打码。 */
interface DiagLog {
    fun log(tag: String, message: String)
    val entries: StateFlow<List<DiagEntry>>
}

interface AppState {
    val isForeground: StateFlow<Boolean>
}
