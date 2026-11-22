package org.xmyun.starmate.core.llm.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 整个模块共用一个 Ktor HttpClient（OkHttp 引擎）。
 * 刻意不装任何日志插件：请求头里有 key，需要记录时只把方法、主机和状态码写进 DiagLog。
 */
@Singleton
class HttpClientFactory @Inject constructor() {

    val client: HttpClient by lazy { create() }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val SOCKET_TIMEOUT_MS = 120_000L

        /** 参数只给测试用；正式代码用 [client]。 */
        fun create(
            connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
            socketTimeoutMs: Long = SOCKET_TIMEOUT_MS,
        ): HttpClient = HttpClient(OkHttp) {
            expectSuccess = false
            engine {
                config {
                    connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                    readTimeout(socketTimeoutMs, TimeUnit.MILLISECONDS)
                    writeTimeout(socketTimeoutMs, TimeUnit.MILLISECONDS)
                }
            }
            install(HttpTimeout) {
                connectTimeoutMillis = connectTimeoutMs
                // 流式回复的两次数据间隔上限；整个请求不设总时长
                socketTimeoutMillis = socketTimeoutMs
            }
        }
    }
}
