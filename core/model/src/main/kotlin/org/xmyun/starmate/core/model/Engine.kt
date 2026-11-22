package org.xmyun.starmate.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface UserInput {
    data class Text(val text: String, val attachments: List<Attachment> = emptyList()) : UserInput

    /** 让她先开口，不产生用户消息。 */
    data class Kickoff(val purpose: TurnPurpose) : UserInput

    /** 对同一条用户消息重新生成，替换失败的那条助手消息。 */
    data class Retry(val failedAssistantMessageId: Long) : UserInput
}

sealed interface TurnEvent {
    data class Started(val userMessageId: Long?, val assistantMessageId: Long) : TurnEvent

    /** 到目前为止应该显示的全部文字，已经过各 ReplyPostProcessor.preview。 */
    data class Text(val displaySoFar: String) : TurnEvent
    data class ToolStarted(val callId: String, val toolName: String, val summary: String) : TurnEvent
    data class ToolFinished(val callId: String, val isError: Boolean) : TurnEvent
    data class Completed(val message: ChatMessage) : TurnEvent
    data class Failed(val assistantMessageId: Long, val error: LlmException) : TurnEvent
}

data class HeadlessTurn(
    val purpose: TurnPurpose,
    /** 作为唯一的用户消息发给模型，不存入聊天记录。 */
    val instruction: String,
    val role: ModelRole = ModelRole.BACKGROUND,
    /** null：带人设（准则 + 人设 + 各 stable 片段）。非 null：只用这段 system prompt。 */
    val systemOverride: String? = null,
    /** 带人设时，把最近多少条聊天记录放在 instruction 之前。 */
    val includeRecentMessages: Int = 0,
    val jsonOutput: Boolean = false,
    val maxOutputTokens: Int? = null,
)

sealed interface HeadlessResult {
    data class Success(val text: String, val usage: TokenUsage) : HeadlessResult
    data object BudgetExceeded : HeadlessResult
    data class Failed(val error: LlmException) : HeadlessResult
}

interface ConversationEngine {
    /** 有前台对话正在进行。 */
    val isBusy: StateFlow<Boolean>

    /** 前台对话。同一时间只允许一轮；上一轮没结束时再调用，流里直接给出 Failed。 */
    fun send(input: UserInput): Flow<TurnEvent>

    /** 后台调用。不给工具，不存消息，检查预算并记账。 */
    suspend fun runHeadless(turn: HeadlessTurn): HeadlessResult

    /** 把一段文字作为她的消息存入聊天记录：过 ReplyPostProcessor，通知 TurnListener。主动消息用。 */
    suspend fun deliver(rawText: String, purpose: TurnPurpose): ChatMessage
}

data class CompletedTurn(
    val purpose: TurnPurpose,
    val userMessage: ChatMessage?,
    val assistantMessage: ChatMessage,
    val toolCalls: List<ToolCall>,
)

interface TurnListener {
    /** 在引擎的协程里被依次调用。耗时工作要自己另起协程。 */
    suspend fun onTurnCompleted(turn: CompletedTurn)
}

data class ProcessedReply(val displayText: String, val attachments: List<Attachment>)

interface ReplyPostProcessor {
    val order: Int

    /** 流式过程中对累计的原始文本调用，返回此刻可以显示的文字。必须是纯函数且很快。 */
    fun preview(rawSoFar: String): String = rawSoFar

    /** 回复完整后调用一次。按 order 依次传递 reply。 */
    suspend fun finish(rawText: String, reply: ProcessedReply, purpose: TurnPurpose): ProcessedReply = reply
}
