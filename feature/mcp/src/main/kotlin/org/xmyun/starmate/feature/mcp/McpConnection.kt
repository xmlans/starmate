package org.xmyun.starmate.feature.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.ReconnectionOptions
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 连接或调用失败的原因。只有分类和状态码，不带任何外部文字，保证不会漏出 URL 和 key。 */
enum class FailureKind { NOT_CONFIGURED, UNAUTHORIZED, HTTP, UNREACHABLE, TIMEOUT, PROTOCOL }

class McpFailure(val kind: FailureKind, val httpCode: Int? = null) :
    Exception("MCP ${kind.name.lowercase()}" + (httpCode?.let { " (HTTP $it)" } ?: ""))

/** 服务器按 JSON-RPC 报错（例如参数不对）。连接本身是好的。 */
class McpRpcFailure(val code: Int) : Exception("MCP JSON-RPC error $code")

/** 星伴自己的 Ktor 客户端：OkHttp 引擎，不装日志插件，不装 SSE 插件（不开常驻的 GET 流）。 */
@Singleton
class McpHttp @Inject constructor() {
    val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) { connectTimeoutMillis = 15_000 }
        }
    }
}

/**
 * 一个 MCP 服务器的连接。第一次用到时才连接；出错后丢掉会话，下次调用重新连接。
 * key 每次连接时从 keyProvider 取，作为查询参数在每个请求发出时加到地址上，不存进任何字段。
 */
class McpConnection(
    private val preset: McpPreset,
    private val baseUrl: String,
    private val http: HttpClient,
    private val keyProvider: suspend () -> String?,
    private val callTimeout: Duration = CALL_TIMEOUT,
) {
    private val mutex = Mutex()
    private var client: Client? = null

    suspend fun listTools(): List<RemoteTool> = session { c ->
        val result = mutableListOf<RemoteTool>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = c.listTools(
                ListToolsRequest(PaginatedRequestParams(cursor = cursor)),
                RequestOptions(timeout = callTimeout),
            )
            page.tools.forEach { tool ->
                val schema = tool.inputSchema
                result += RemoteTool(
                    name = tool.name,
                    description = tool.description.orEmpty(),
                    inputSchema = buildJsonObject {
                        put("type", "object")
                        put("properties", schema.properties ?: JsonObject(emptyMap()))
                        schema.required?.takeIf { it.isNotEmpty() }?.let { req ->
                            put("required", JsonArray(req.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                        }
                        schema.defs?.let { put("\$defs", it) }
                    },
                )
            }
            cursor = page.nextCursor
            pages++
        } while (cursor != null && pages < MAX_PAGES)
        result
    }

    suspend fun callTool(name: String, arguments: JsonObject): CallToolResult = session { c ->
        c.callTool(
            CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)),
            RequestOptions(timeout = callTimeout),
        )
    }

    suspend fun close() = mutex.withLock { dropSession() }

    private suspend fun <T> session(block: suspend (Client) -> T): T = mutex.withLock {
        try {
            withTimeout(callTimeout) {
                val c = client ?: connect().also { client = it }
                block(c)
            }
        } catch (e: TimeoutCancellationException) {
            withContext(NonCancellable) { dropSession() }
            // 超时可能来自外层调用方（例如引擎限制收集工具的时间）：那不是这个服务器的失败，原样取消
            currentCoroutineContext().ensureActive()
            throw McpFailure(FailureKind.TIMEOUT)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dropSession() }
            throw e
        } catch (e: McpFailure) {
            dropSession()
            throw e
        } catch (e: Exception) {
            val rpc = e.rpcCode()
            if (rpc == REQUEST_TIMEOUT_CODE) {
                dropSession()
                throw McpFailure(FailureKind.TIMEOUT)
            }
            if (rpc != null && client != null) throw McpRpcFailure(rpc)
            dropSession()
            throw classify(e)
        }
    }

    private suspend fun connect(): Client {
        val key = keyProvider()?.takeIf { it.isNotBlank() } ?: throw McpFailure(FailureKind.NOT_CONFIGURED)
        val transport = StreamableHttpClientTransport(
            client = http,
            url = baseUrl,
            // 不装 SSE 插件，GET 流会立即失败并停止重试；请求的回复都从 POST 响应里拿
            reconnectionOptions = ReconnectionOptions(maxRetries = 1),
            requestBuilder = { url.parameters.append(preset.keyParam, key) },
        )
        val c = Client(
            Implementation(name = "starmate", version = "1.0"),
            ClientOptions(enforceStrictCapabilities = false),
        )
        c.connect(transport)
        return c
    }

    private suspend fun dropSession() {
        val old = client ?: return
        client = null
        try {
            old.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 关闭失败不影响下次重连
        }
    }

    /** 服务器回的 JSON-RPC 错误：没有 cause 的 McpException。SDK 把发送失败也包成 McpException，但带着 cause。 */
    private fun Throwable.rpcCode(): Int? {
        var t: Throwable? = this
        while (t != null) {
            if (t is McpException && t.cause == null) return t.code
            t = t.cause
        }
        return null
    }

    companion object {
        val CALL_TIMEOUT: Duration = 30.seconds
        private const val MAX_PAGES = 5

        /** SDK 自己的请求超时用的 JSON-RPC 错误码。 */
        private const val REQUEST_TIMEOUT_CODE = -32001

        /** 只看异常类型和状态码，不使用任何异常消息。 */
        fun classify(e: Throwable): McpFailure {
            var t: Throwable? = e
            while (t != null) {
                when (t) {
                    is McpFailure -> return t
                    is StreamableHttpError -> {
                        val code = t.code
                        return when (code) {
                            401, 403 -> McpFailure(FailureKind.UNAUTHORIZED, code)
                            null, -1 -> McpFailure(FailureKind.PROTOCOL)
                            else -> McpFailure(FailureKind.HTTP, code)
                        }
                    }
                    is TimeoutCancellationException -> return McpFailure(FailureKind.TIMEOUT)
                    is IOException -> return if (t.javaClass.simpleName.contains("Timeout")) {
                        McpFailure(FailureKind.TIMEOUT)
                    } else {
                        McpFailure(FailureKind.UNREACHABLE)
                    }
                    is SerializationException -> return McpFailure(FailureKind.PROTOCOL)
                    // 带 cause 的是 SDK 包装的发送失败，继续往里看
                    is McpException -> if (t.cause == null) return McpFailure(FailureKind.PROTOCOL)
                }
                t = t.cause
            }
            return McpFailure(FailureKind.PROTOCOL)
        }
    }
}

/** 测试里可以替换。 */
interface McpConnectionFactory {
    fun create(preset: McpPreset, keyProvider: suspend () -> String?): McpConnection
}

class DefaultMcpConnectionFactory @Inject constructor(
    private val http: McpHttp,
    private val endpoints: McpEndpoints,
) : McpConnectionFactory {
    override fun create(preset: McpPreset, keyProvider: suspend () -> String?): McpConnection =
        McpConnection(preset, endpoints.baseUrl(preset), http.client, keyProvider)
}
