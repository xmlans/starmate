package org.xmyun.starmate.feature.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.SecretStore

sealed interface ServerStatus {
    /** 没填 key。 */
    data object NotConfigured : ServerStatus

    /** 填了 key，但这次运行还没连过；有未过期的缓存时照常提供工具。 */
    data object Unchecked : ServerStatus
    data object Ok : ServerStatus
    data class Failed(val kind: FailureKind, val httpCode: Int?, val at: Instant) : ServerStatus
}

data class ServerState(
    val status: ServerStatus = ServerStatus.Unchecked,
    val tools: List<RemoteTool> = emptyList(),
    val fetchedAt: Instant? = null,
)

sealed interface CallOutcome {
    data class Success(val result: CallToolResult) : CallOutcome
    data class Failure(val kind: FailureKind, val httpCode: Int?) : CallOutcome
    data class RpcError(val code: Int) : CallOutcome
}

/**
 * 每个预设的连接、状态和工具列表缓存。
 * - tools/list 结果缓存 24 小时（存 mcp_prefs）；改 key 或点“测试”时刷新。
 * - 连接失败的服务器本轮不提供工具；5 分钟内不再自动重试，免得每轮聊天都卡在连接上。
 */
@Singleton
class McpRegistry @Inject constructor(
    private val prefs: McpPrefs,
    private val secrets: SecretStore,
    private val factory: McpConnectionFactory,
    private val clock: Clock,
    private val diag: DiagLog,
) {
    private val state = MutableStateFlow(McpPreset.entries.associateWith { ServerState() })
    val states: StateFlow<Map<McpPreset, ServerState>> = state.asStateFlow()

    private val connections = HashMap<McpPreset, McpConnection>()
    private val locks = McpPreset.entries.associateWith { Mutex() }
    private val connectionsLock = Mutex()

    private suspend fun connection(preset: McpPreset): McpConnection = connectionsLock.withLock {
        connections.getOrPut(preset) { factory.create(preset) { secrets.get(preset.secretName) } }
    }

    private fun setState(preset: McpPreset, transform: (ServerState) -> ServerState) {
        state.update { it + (preset to transform(it.getValue(preset))) }
    }

    private suspend fun hasKey(preset: McpPreset) = !secrets.get(preset.secretName).isNullOrBlank()

    /** 聊天这一轮要提供的工具（原名）。不可用时返回空列表。 */
    suspend fun toolsForChat(preset: McpPreset): List<RemoteTool> = locks.getValue(preset).withLock {
        if (!prefs.isEnabled(preset)) return emptyList()
        if (!hasKey(preset)) {
            setState(preset) { ServerState(ServerStatus.NotConfigured) }
            return emptyList()
        }
        val current = state.value.getValue(preset)
        val status = current.status
        if (status is ServerStatus.Failed && Duration.between(status.at, clock.instant()) < RETRY_COOLDOWN) {
            return emptyList()
        }
        if (status !is ServerStatus.Failed) {
            val cached = prefs.cachedTools(preset)
            if (cached != null && fresh(cached.fetchedAt)) {
                if (current.fetchedAt != cached.fetchedAt) {
                    setState(preset) { it.copy(tools = cached.tools, fetchedAt = cached.fetchedAt) }
                }
                return cached.tools
            }
        }
        // 聊天这条路不能久等：超过这个时间就按超时记失败并进入冷却，避免每一轮都卡在这里。
        // 设置页的“测试”走 refresh()，仍然用完整的调用超时。
        val finished = withTimeoutOrNull(CHAT_REFRESH_TIMEOUT.toMillis()) {
            refreshLocked(preset)
            true
        }
        if (finished == null) markFailed(preset, FailureKind.TIMEOUT, null)
        val after = state.value.getValue(preset)
        return if (after.status == ServerStatus.Ok) after.tools else emptyList()
    }

    /** “测试”按钮和改 key 后调用：无视缓存重新取 tools/list。 */
    suspend fun refresh(preset: McpPreset): ServerState = locks.getValue(preset).withLock {
        refreshLocked(preset)
        state.value.getValue(preset)
    }

    private suspend fun refreshLocked(preset: McpPreset) {
        if (!hasKey(preset)) {
            setState(preset) { ServerState(ServerStatus.NotConfigured) }
            return
        }
        try {
            val tools = connection(preset).listTools()
            val now = clock.instant()
            prefs.saveTools(preset, tools, now)
            setState(preset) { ServerState(ServerStatus.Ok, tools, now) }
            diag.log(TAG, "${preset.id}: ${tools.size} tools listed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = (e as? McpFailure) ?: McpConnection.classify(e)
            markFailed(preset, failure.kind, failure.httpCode)
        }
    }

    suspend fun call(preset: McpPreset, originalName: String, arguments: JsonObject): CallOutcome = try {
        val result = connection(preset).callTool(originalName, arguments)
        if (state.value.getValue(preset).status != ServerStatus.Ok) setState(preset) { it.copy(status = ServerStatus.Ok) }
        CallOutcome.Success(result)
    } catch (e: CancellationException) {
        throw e
    } catch (e: McpRpcFailure) {
        CallOutcome.RpcError(e.code)
    } catch (e: Exception) {
        val failure = (e as? McpFailure) ?: McpConnection.classify(e)
        markFailed(preset, failure.kind, failure.httpCode)
        CallOutcome.Failure(failure.kind, failure.httpCode)
    }

    /** key 改了：断开旧连接、清掉缓存，有 key 时立即刷新。 */
    suspend fun onKeyChanged(preset: McpPreset) {
        locks.getValue(preset).withLock {
            connectionsLock.withLock { connections.remove(preset) }?.close()
            prefs.clearTools(preset)
            val keyed = hasKey(preset)
            setState(preset) { ServerState(if (keyed) ServerStatus.Unchecked else ServerStatus.NotConfigured) }
            if (keyed) refreshLocked(preset)
        }
    }

    /** 设置页打开时：把缓存和“是否填了 key”反映到状态上，不发请求。 */
    suspend fun loadCached(preset: McpPreset) {
        val keyed = hasKey(preset)
        val cached = prefs.cachedTools(preset)
        setState(preset) { s ->
            when {
                !keyed -> ServerState(ServerStatus.NotConfigured)
                s.status == ServerStatus.NotConfigured -> ServerState(ServerStatus.Unchecked, cached?.tools.orEmpty(), cached?.fetchedAt)
                s.tools.isEmpty() && cached != null -> s.copy(tools = cached.tools, fetchedAt = cached.fetchedAt)
                else -> s
            }
        }
    }

    private fun markFailed(preset: McpPreset, kind: FailureKind, httpCode: Int?) {
        setState(preset) { it.copy(status = ServerStatus.Failed(kind, httpCode, clock.instant())) }
        diag.log(TAG, "${preset.id}: connection failed (${kind.name}${httpCode?.let { c -> " $c" } ?: ""})")
    }

    private fun fresh(at: Instant) = Duration.between(at, clock.instant()) < CACHE_TTL

    companion object {
        val CACHE_TTL: Duration = Duration.ofHours(24)
        val RETRY_COOLDOWN: Duration = Duration.ofMinutes(5)

        /** 聊天时刷新工具列表最多等这么久，要小于引擎给每个工具提供者的上限（8 秒）。 */
        val CHAT_REFRESH_TIMEOUT: Duration = Duration.ofSeconds(5)
        private const val TAG = "mcp"
    }
}
