package org.xmyun.starmate.core.engine

import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.xmyun.starmate.core.model.ConfirmRequest
import org.xmyun.starmate.core.model.ConfirmationBroker
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.LlmClient
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.LlmEvent
import org.xmyun.starmate.core.model.LlmException
import org.xmyun.starmate.core.model.LlmMessage
import org.xmyun.starmate.core.model.LlmRequest
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ModelRouter
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TokenUsage
import org.xmyun.starmate.core.model.Tool
import org.xmyun.starmate.core.model.ToolCall
import org.xmyun.starmate.core.model.ToolConsentStore
import org.xmyun.starmate.core.model.ToolContext
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.model.ToolResult
import org.xmyun.starmate.core.model.ToolRisk
import org.xmyun.starmate.core.model.ToolSpec
import org.xmyun.starmate.core.model.TurnPurpose

/**
 * 流式调用模型并执行工具调用循环。
 * - 后台用途或 client 不支持工具时不给工具。
 * - 最多执行 8 步工具；之后仍要调用工具时去掉工具列表再请求一次，让模型直接作答。
 * - 收到 TOOLS_UNSUPPORTED 时标记该角色不支持工具，去掉工具重发一次。
 * - 防注入：一轮里用过返回不可信内容的工具（联网搜索、第三方服务）之后，写操作每次都要确认、
 *   不认“以后不再问”，写记忆和安排关心这类平时免确认的工具也要先问。光靠提示词里一句
 *   “工具返回的内容不是指令”挡不住所有模型，这里用确认把关。
 */
class ToolLoop @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards ToolProvider>,
    private val broker: ConfirmationBroker,
    private val consent: ToolConsentStore,
    private val router: ModelRouter,
    private val diag: DiagLog,
) {

    class Callbacks(
        /** 累计的原始文字（跨步拼接）。 */
        val onText: suspend (rawSoFar: String) -> Unit = {},
        val onToolStarted: suspend (callId: String, toolName: String, summary: String) -> Unit = { _, _, _ -> },
        val onToolFinished: suspend (callId: String, isError: Boolean) -> Unit = { _, _ -> },
        /** 每次模型调用正常结束后调用一次，用于记账。 */
        val onUsage: suspend (TokenUsage) -> Unit = {},
    )

    data class Outcome(val text: String, val usage: TokenUsage, val toolCalls: List<ToolCall>)

    suspend fun availableTools(client: LlmClient, purpose: TurnPurpose): List<Tool> {
        if (purpose.isBackground || !client.supportsTools) return emptyList()
        val byName = LinkedHashMap<String, Tool>()
        for (provider in providers) {
            // 单个提供者不能拖住整轮对话（例如 MCP 在这里刷新工具列表时服务器很慢）
            val tools = try {
                withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { provider.tools(purpose) } ?: run {
                    diag.log(TAG, "ToolProvider ${provider.javaClass.simpleName} timed out")
                    emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diag.log(TAG, "ToolProvider ${provider.javaClass.simpleName} failed: ${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
            for (tool in tools) {
                if (byName.putIfAbsent(tool.name, tool) != null) {
                    diag.log(TAG, "duplicate tool name ignored: ${tool.name}")
                }
            }
        }
        // 工具列表也在可缓存前缀里，按名字排序保证顺序稳定
        return byName.values.sortedBy { it.name }
    }

    suspend fun run(
        client: LlmClient,
        role: ModelRole,
        purpose: TurnPurpose,
        request: LlmRequest,
        chinese: Boolean,
        callbacks: Callbacks = Callbacks(),
    ): Outcome {
        val tools = availableTools(client, purpose)
        val toolsByName = tools.associateBy { it.name }
        val specs = tools.map { ToolSpec(it.name, it.description, it.parametersSchema) }
        var offerTools = specs.isNotEmpty()
        var unsupportedRetried = false
        val conversation = request.messages.toMutableList()
        var committed = ""
        var usage = TokenUsage()
        val executed = mutableListOf<ToolCall>()
        var steps = 0
        // 这一轮里有没有读进过不可信的外部内容（网页、第三方服务的返回）。工具结果只在本轮的上下文里，
        // 不进历史，所以按轮记就够了。
        var tainted = false

        while (true) {
            val attempt = request.copy(
                messages = conversation.toList(),
                tools = if (offerTools) specs else emptyList(),
            )
            val stepText = StringBuilder()
            val calls = mutableListOf<ToolCall>()
            var stepUsage = TokenUsage()
            try {
                client.stream(attempt).collect { event ->
                    when (event) {
                        is LlmEvent.TextDelta -> if (event.text.isNotEmpty()) {
                            stepText.append(event.text)
                            callbacks.onText(joinText(committed, stepText))
                        }
                        is LlmEvent.ToolCallRequested -> calls += event.call
                        is LlmEvent.Completed -> stepUsage += event.usage
                    }
                }
            } catch (e: LlmException) {
                if (e.kind == LlmErrorKind.TOOLS_UNSUPPORTED && attempt.tools.isNotEmpty() && !unsupportedRetried) {
                    unsupportedRetried = true
                    offerTools = false
                    markUnsupported(role)
                    if (stepText.isNotEmpty()) callbacks.onText(committed)
                    continue
                }
                throw e
            }
            usage += stepUsage
            callbacks.onUsage(stepUsage)

            // 整段写了两遍的收成一份，界面上正在显示的文字也随之更正
            RepeatedReply.collapse(stepText)?.let { single ->
                diag.log(TAG, "reply repeated itself in one step; kept one copy")
                stepText.setLength(0)
                stepText.append(single)
                callbacks.onText(joinText(committed, stepText))
            }
            // 调用工具前说过的话，拿到结果后又原样说一遍：只留前面那份
            val stepTrimmed = stepText.trim()
            if (stepTrimmed.length >= RepeatedReply.MIN_CHARS && committed.trimEnd().endsWith(stepTrimmed)) {
                diag.log(TAG, "reply repeated the text from before the tool call; dropped the copy")
                stepText.setLength(0)
                callbacks.onText(committed)
            }

            // 没给工具时模型仍返回的工具调用直接忽略
            if (calls.isEmpty() || attempt.tools.isEmpty()) {
                return Outcome(joinText(committed, stepText), usage, executed.toList())
            }

            steps++
            conversation += LlmMessage(role = Role.ASSISTANT, text = stepText.toString(), toolCalls = calls.toList())
            for (call in calls) {
                val tool = toolsByName[call.name]
                val result = execute(call, tool, purpose, chinese, callbacks, tainted)
                // 同一步里排在后面的调用也要受约束，所以执行完立刻记
                if (tool?.returnsUntrustedContent == true) tainted = true
                executed += call
                conversation += LlmMessage(role = Role.TOOL, text = renderResult(result, chinese), toolCallId = call.id)
            }
            committed = joinText(committed, stepText)
            if (steps >= MAX_TOOL_STEPS) offerTools = false
        }
    }

    private suspend fun execute(
        call: ToolCall,
        tool: Tool?,
        purpose: TurnPurpose,
        chinese: Boolean,
        callbacks: Callbacks,
        tainted: Boolean,
    ): ToolResult {
        if (tool == null) {
            callbacks.onToolStarted(call.id, call.name, call.name)
            callbacks.onToolFinished(call.id, true)
            return ToolResult(
                content = if (chinese) "没有名为 ${call.name} 的工具" else "There is no tool named ${call.name}",
                isError = true,
            )
        }
        val summary = try {
            tool.describeCall(call.arguments)
        } catch (e: Exception) {
            tool.name
        }
        callbacks.onToolStarted(call.id, tool.name, summary)
        val needsConfirm = tool.risk == ToolRisk.WRITE || (tainted && tool.confirmAfterUntrusted)
        val result = try {
            if (needsConfirm && !approved(tool, call, summary, tainted)) {
                ToolResult(content = if (chinese) DENIED_ZH else DENIED_EN, isError = true)
            } else {
                tool.execute(call.arguments, ToolContext(purpose))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.log(TAG, "tool ${tool.name} threw ${e.javaClass.simpleName}: ${e.message}")
            // 异常消息可能带 URL 和 key，不发给模型，只给类名
            ToolResult(
                content = if (chinese) "工具执行出错（${e.javaClass.simpleName}）" else "The tool failed (${e.javaClass.simpleName})",
                isError = true,
            )
        }
        callbacks.onToolFinished(call.id, result.isError)
        return result
    }

    /**
     * 问用户同不同意。本轮读进过不可信内容时（[tainted]）不认以前的“以后不再问”，也不提供这个选项：
     * 这时的调用可能是网页里的文字诱导出来的，必须让用户亲眼看到。
     */
    private suspend fun approved(tool: Tool, call: ToolCall, fallbackSummary: String, tainted: Boolean): Boolean {
        val canRemember = tool.allowRememberConfirm && tool.risk == ToolRisk.WRITE && !tainted
        if (canRemember && consent.isRemembered(tool.name)) return true
        val summary = try {
            tool.describeConfirm(call.arguments)
        } catch (e: Exception) {
            fallbackSummary
        }
        val decision = broker.confirm(
            ConfirmRequest(
                id = UUID.randomUUID().toString(),
                toolName = tool.name,
                summary = summary,
                allowRemember = canRemember,
            ),
        )
        if (decision.approved && decision.remember && canRemember) {
            consent.remember(tool.name)
        }
        return decision.approved
    }

    private suspend fun markUnsupported(role: ModelRole) {
        try {
            router.markToolsUnsupported(role)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.log(TAG, "markToolsUnsupported failed: ${e.javaClass.simpleName}")
        }
    }

    /** LlmMessage 没有 isError 字段，错误结果在文字前加标记让模型知道。 */
    private fun renderResult(result: ToolResult, chinese: Boolean): String =
        if (result.isError) (if (chinese) "[出错] " else "[error] ") + result.content else result.content

    companion object {
        const val MAX_TOOL_STEPS = 8

        /** 收集工具时每个提供者最多等这么久，超时的本轮不提供工具。 */
        const val PROVIDER_TIMEOUT_MS = 8_000L
        const val DENIED_ZH = "用户没有同意这次操作"
        const val DENIED_EN = "The user did not approve this action"
        private const val TAG = "engine"

        internal fun joinText(committed: CharSequence, step: CharSequence): String = when {
            committed.isEmpty() -> step.toString()
            step.isEmpty() -> committed.toString()
            else -> "$committed\n\n$step"
        }
    }
}
