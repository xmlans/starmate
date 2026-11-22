package org.xmyun.starmate.feature.mcp

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/** 最小的 Streamable HTTP MCP 服务端：支持 initialize、tools/list、tools/call，GET 回 405。 */
class MockMcpServer(
    private val keyParam: String,
    var toolNames: List<String> = listOf("tavily_search"),
) : Closeable {

    private val server = MockWebServer()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    val keysSeen = CopyOnWriteArrayList<String?>()

    /** tools/call 的回复：(工具名, 参数) -> (文本, 是否出错)。 */
    var onCall: (String, JsonObject) -> Pair<String, Boolean> = { name, _ -> "result of $name" to false }

    /** 非空时所有 POST 都回这个状态码，正文里回显请求地址（模拟把 URL 打进错误信息的服务器）。 */
    var failWith: Int? = null

    fun count(method: String): Int = counts[method]?.get() ?: 0

    fun start(): String {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
        return server.url("/mcp").toString()
    }

    private fun handle(request: RecordedRequest): MockResponse {
        keysSeen += request.url.queryParameter(keyParam)
        if (request.method != "POST") return MockResponse.Builder().code(405).build()
        failWith?.let { code ->
            return MockResponse.Builder().code(code).body("denied for ${request.url}").build()
        }
        val body = request.body?.utf8().orEmpty()
        val message = Json.parseToJsonElement(body).jsonObject
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        counts.getOrPut(method) { AtomicInteger() }.incrementAndGet()
        val id = message["id"]
        if (id == null || id is JsonNull) return MockResponse.Builder().code(202).build()
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        val result: JsonElement = when (method) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", params["protocolVersion"] ?: JsonPrimitive("2025-06-18"))
                put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                put("serverInfo", buildJsonObject { put("name", "mock"); put("version", "1.0") })
            }
            "tools/list" -> buildJsonObject {
                put("tools", buildJsonArray {
                    toolNames.forEach { name ->
                        add(buildJsonObject {
                            put("name", name)
                            put("description", "Mock tool $name")
                            put("inputSchema", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("query", buildJsonObject { put("type", "string") })
                                })
                                put("required", buildJsonArray { add(JsonPrimitive("query")) })
                            })
                        })
                    }
                })
            }
            "tools/call" -> {
                val name = (params["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val (text, isError) = onCall(name, params["arguments"] as? JsonObject ?: JsonObject(emptyMap()))
                buildJsonObject {
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", text) })
                    })
                    put("isError", isError)
                }
            }
            else -> return json(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("error", buildJsonObject { put("code", -32601); put("message", "Method not found") })
            })
        }
        return json(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        })
    }

    private fun json(obj: JsonObject) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .addHeader("mcp-session-id", "session-1")
        .body(obj.toString())
        .build()

    override fun close() = server.close()
}
