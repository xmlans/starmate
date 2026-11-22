package org.xmyun.starmate.core.engine

import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.xmyun.starmate.core.engine.guidelines.GuidelinesSource
import org.xmyun.starmate.core.model.ATTACHMENT_PREVIEW_KEY
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.CompletedTurn
import org.xmyun.starmate.core.model.ConversationEngine
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.HeadlessResult
import org.xmyun.starmate.core.model.HeadlessTurn
import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.core.model.LlmEvent
import org.xmyun.starmate.core.model.LlmException
import org.xmyun.starmate.core.model.LlmMessage
import org.xmyun.starmate.core.model.LlmRequest
import org.xmyun.starmate.core.model.MessageStatus
import org.xmyun.starmate.core.model.MessageStore
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ModelRouter
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.ProcessedReply
import org.xmyun.starmate.core.model.ReplyPostProcessor
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.SystemPrompt
import org.xmyun.starmate.core.model.TokenUsage
import org.xmyun.starmate.core.model.TurnEvent
import org.xmyun.starmate.core.model.TurnListener
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UsageEntry
import org.xmyun.starmate.core.model.UsageLedger
import org.xmyun.starmate.core.model.UserInput

/**
 * 对话引擎。
 *
 * 并发：前台 send 用 [turnMutex] 保证同一时间只有一轮；runHeadless 和 deliver 不拿这把锁，
 * 它们只用局部状态，可以和前台对话并发（TurnListener 在前台轮次里调用 runHeadless 也不会死锁）。
 *
 * 取消：收集方取消时，那条 STREAMING 的助手消息改成 FAILED（errorKind = UNKNOWN，保留已生成的部分文字），
 * 这样聊天页能在它上面显示“重试”。
 */
@Singleton
class DefaultConversationEngine @Inject constructor(
    private val messages: MessageStore,
    private val prefs: CompanionPrefs,
    private val ledger: UsageLedger,
    private val router: ModelRouter,
    private val diag: DiagLog,
    private val guidelines: GuidelinesSource,
    private val assembler: PromptAssembler,
    private val toolLoop: ToolLoop,
    listeners: Set<@JvmSuppressWildcards TurnListener>,
    postProcessors: Set<@JvmSuppressWildcards ReplyPostProcessor>,
    private val clock: Clock,
) : ConversationEngine {

    private val listeners = listeners.sortedBy { it.javaClass.name }
    private val processors = postProcessors.sortedWith(
        compareBy<ReplyPostProcessor>({ it.order }, { it.javaClass.name }),
    )

    private val turnMutex = Mutex()
    private val _isBusy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    // channelFlow：收集方抛出的异常以取消的形式传进来，不会和我们自己的错误处理混在一起
    override fun send(input: UserInput): Flow<TurnEvent> = channelFlow {
        val chinese = guidelines.load().chinese
        if (!turnMutex.tryLock()) {
            send(
                TurnEvent.Failed(
                    NO_MESSAGE_ID,
                    LlmException(LlmErrorKind.UNKNOWN, pick(chinese, "上一条还没说完", "The previous reply isn't finished yet")),
                ),
            )
            return@channelFlow
        }
        _isBusy.value = true
        try {
            runTurn(input, chinese) { send(it) }
        } finally {
            _isBusy.value = false
            turnMutex.unlock()
        }
    }

    private class TurnSetup(
        val purpose: TurnPurpose,
        val userMessage: ChatMessage?,
        /** 本轮用户消息的 rawText；Kickoff 为 null。 */
        val currentRaw: String?,
        /** 交给 PromptContributor.dynamic 的用户原话。 */
        val userText: String?,
        /** 本轮之前的消息，按时间正序，未裁剪。 */
        val history: List<ChatMessage>,
    )

    private suspend fun runTurn(input: UserInput, chinese: Boolean, emit: suspend (TurnEvent) -> Unit) {
        var placeholder: ChatMessage? = null
        var partialRaw = ""
        var completed = false
        try {
            val setup = prepare(input, chinese)
            val draft = ChatMessage(
                role = Role.ASSISTANT,
                rawText = "",
                displayText = "",
                purpose = setup.purpose,
                createdAt = clock.instant(),
                status = MessageStatus.STREAMING,
            )
            val assistant = draft.copy(id = messages.insert(draft))
            placeholder = assistant
            emit(TurnEvent.Started(setup.userMessage?.id, assistant.id))

            val client = router.chat(ModelRole.CHAT)
                ?: throw LlmException(LlmErrorKind.NOT_CONFIGURED, pick(chinese, "还没有配置聊天模型", "No chat model is configured"))
            val request = buildRequest(setup)
            val outcome = toolLoop.run(
                client = client,
                role = ModelRole.CHAT,
                purpose = setup.purpose,
                request = request,
                chinese = chinese,
                callbacks = ToolLoop.Callbacks(
                    onText = { raw ->
                        partialRaw = raw
                        emit(TurnEvent.Text(preview(raw)))
                    },
                    onToolStarted = { id, name, summary -> emit(TurnEvent.ToolStarted(id, name, summary)) },
                    onToolFinished = { id, isError -> emit(TurnEvent.ToolFinished(id, isError)) },
                    onUsage = { usage -> record(setup.purpose, client.modelId, usage) },
                ),
            )
            if (outcome.text.isBlank()) {
                throw LlmException(LlmErrorKind.BAD_RESPONSE, pick(chinese, "模型没有返回内容", "The model returned nothing"))
            }
            val reply = finish(outcome.text, setup.purpose)
            val done = assistant.copy(
                rawText = outcome.text,
                displayText = reply.displayText,
                attachments = reply.attachments,
                status = MessageStatus.COMPLETE,
                errorKind = null,
            )
            messages.update(done)
            completed = true
            notifyListeners(CompletedTurn(setup.purpose, setup.userMessage, done, outcome.toolCalls))
            emit(TurnEvent.Completed(done))
        } catch (e: CancellationException) {
            val pending = placeholder
            if (pending != null && !completed) {
                withContext(NonCancellable) { markFailed(pending, partialRaw, LlmErrorKind.UNKNOWN) }
            }
            throw e
        } catch (e: Exception) {
            val error = e as? LlmException
                ?: LlmException(LlmErrorKind.UNKNOWN, e.message ?: e.javaClass.simpleName, cause = e)
            diag.log(TAG, "turn failed: ${error.kind} ${error.message}")
            val pending = placeholder
            if (pending != null && !completed) markFailed(pending, partialRaw, error.kind)
            emit(TurnEvent.Failed(pending?.id ?: NO_MESSAGE_ID, error))
        }
    }

    private suspend fun prepare(input: UserInput, chinese: Boolean): TurnSetup = when (input) {
        is UserInput.Text -> {
            val purpose = foregroundPurpose()
            val history = messages.recent(HISTORY_FETCH_LIMIT)
            val raw = composeUserRaw(input.text, input.attachments)
            val user = ChatMessage(
                role = Role.USER,
                rawText = raw,
                displayText = input.text,
                attachments = input.attachments,
                purpose = purpose,
                createdAt = clock.instant(),
            )
            val stored = user.copy(id = messages.insert(user))
            TurnSetup(purpose, stored, raw, input.text, history)
        }

        is UserInput.Kickoff ->
            TurnSetup(input.purpose, null, null, null, messages.recent(HISTORY_FETCH_LIMIT))

        is UserInput.Retry -> prepareRetry(input, chinese)
    }

    private suspend fun prepareRetry(input: UserInput.Retry, chinese: Boolean): TurnSetup {
        val failed = messages.byId(input.failedAssistantMessageId)?.takeIf { it.role == Role.ASSISTANT }
            ?: throw LlmException(LlmErrorKind.UNKNOWN, pick(chinese, "找不到要重试的消息", "The message to retry was not found"))
        val recent = messages.recent(HISTORY_FETCH_LIMIT)
        val index = recent.indexOfFirst { it.id == failed.id }
        val before = if (index >= 0) {
            recent.subList(0, index).toList()
        } else {
            recent.filter { it.id != failed.id && it.createdAt <= failed.createdAt }
        }
        messages.delete(failed.id)
        // 失败消息之前最近一条可用的消息：是用户消息就用它重跑；否则失败的是一次 Kickoff，按 Kickoff 重跑
        val anchorIndex = before.indexOfLast { HistorySelector.isEligible(it) }
        val anchor = before.getOrNull(anchorIndex)
        return if (anchor != null && anchor.role == Role.USER) {
            TurnSetup(foregroundPurpose(), anchor, anchor.rawText, anchor.displayText, before.subList(0, anchorIndex))
        } else {
            TurnSetup(failed.purpose, null, null, null, before)
        }
    }

    private suspend fun buildRequest(setup: TurnSetup): LlmRequest {
        val stable = assembler.stable(setup.purpose)
        val dynamic = assembler.dynamic(setup.purpose, setup.userText)
        val budget = prefs.historyTokenBudget.first()
        val history = HistorySelector.toLlm(HistorySelector.select(setup.history, budget))
        val current = LlmMessage(role = Role.USER, text = assembler.userContent(setup.currentRaw, dynamic))
        return LlmRequest(system = SystemPrompt(stable = stable), messages = history + current)
    }

    private suspend fun foregroundPurpose(): TurnPurpose =
        if (prefs.onboardingState.first() == OnboardingState.INTERVIEWING) TurnPurpose.ONBOARDING else TurnPurpose.CHAT

    override suspend fun runHeadless(turn: HeadlessTurn): HeadlessResult {
        val chinese = guidelines.load().chinese
        return try {
            if (!ledger.allow(turn.purpose)) {
                diag.log(TAG, "headless ${turn.purpose} skipped: budget exceeded")
                return HeadlessResult.BudgetExceeded
            }
            val client = router.chat(turn.role)
                ?: throw LlmException(LlmErrorKind.NOT_CONFIGURED, pick(chinese, "还没有配置模型", "No model is configured"))
            val system = turn.systemOverride ?: assembler.stable(turn.purpose)
            val recent = if (turn.systemOverride == null && turn.includeRecentMessages > 0) {
                HistorySelector.toLlm(messages.recent(turn.includeRecentMessages).filter(HistorySelector::isEligible))
            } else {
                emptyList()
            }
            val request = LlmRequest(
                system = SystemPrompt(stable = system),
                messages = recent + LlmMessage(role = Role.USER, text = turn.instruction),
                tools = emptyList(),
                maxOutputTokens = turn.maxOutputTokens,
                jsonOutput = turn.jsonOutput,
            )
            val text = StringBuilder()
            var usage = TokenUsage()
            client.stream(request).collect { event ->
                when (event) {
                    is LlmEvent.TextDelta -> text.append(event.text)
                    is LlmEvent.Completed -> usage += event.usage
                    is LlmEvent.ToolCallRequested -> Unit
                }
            }
            record(turn.purpose, client.modelId, usage)
            HeadlessResult.Success(text.toString(), usage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            diag.log(TAG, "headless ${turn.purpose} failed: ${e.kind} ${e.message}")
            HeadlessResult.Failed(e)
        } catch (e: Exception) {
            diag.log(TAG, "headless ${turn.purpose} failed: ${e.javaClass.simpleName}: ${e.message}")
            HeadlessResult.Failed(LlmException(LlmErrorKind.UNKNOWN, e.message ?: e.javaClass.simpleName, cause = e))
        }
    }

    override suspend fun deliver(rawText: String, purpose: TurnPurpose): ChatMessage {
        val reply = finish(rawText, purpose)
        val message = ChatMessage(
            role = Role.ASSISTANT,
            rawText = rawText,
            displayText = reply.displayText,
            attachments = reply.attachments,
            purpose = purpose,
            createdAt = clock.instant(),
            status = MessageStatus.COMPLETE,
        )
        val stored = message.copy(id = messages.insert(message))
        notifyListeners(CompletedTurn(purpose, null, stored, emptyList()))
        return stored
    }

    // ---- 加工与通知 ----

    private fun preview(raw: String): String = processors.fold(raw) { acc, processor ->
        try {
            processor.preview(acc)
        } catch (e: Exception) {
            acc
        }
    }

    private suspend fun finish(raw: String, purpose: TurnPurpose): ProcessedReply {
        var reply = ProcessedReply(displayText = raw, attachments = emptyList())
        for (processor in processors) {
            reply = try {
                processor.finish(raw, reply, purpose)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diag.log(TAG, "ReplyPostProcessor ${processor.javaClass.simpleName} failed: ${e.javaClass.simpleName}: ${e.message}")
                reply
            }
        }
        return reply
    }

    private suspend fun notifyListeners(turn: CompletedTurn) {
        for (listener in listeners) {
            try {
                listener.onTurnCompleted(turn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diag.log(TAG, "TurnListener ${listener.javaClass.simpleName} failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private suspend fun markFailed(placeholder: ChatMessage, partialRaw: String, kind: LlmErrorKind) {
        try {
            messages.update(
                placeholder.copy(
                    rawText = partialRaw,
                    displayText = preview(partialRaw),
                    status = MessageStatus.FAILED,
                    errorKind = kind,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.log(TAG, "could not mark message ${placeholder.id} failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun record(purpose: TurnPurpose, model: String, usage: TokenUsage) {
        try {
            ledger.record(
                UsageEntry(
                    purpose = purpose,
                    model = model,
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    cachedInputTokens = usage.cachedInputTokens,
                    at = clock.instant(),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.log(TAG, "usage record failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        /** Failed 事件里没有对应助手消息时用的 id。 */
        const val NO_MESSAGE_ID = 0L
        const val HISTORY_FETCH_LIMIT = 200
        private const val TAG = "engine"

        private fun pick(chinese: Boolean, zh: String, en: String) = if (chinese) zh else en

        /** 用户消息的 rawText：各附件的 preview 每个一行，再加用户原文。 */
        fun composeUserRaw(text: String, attachments: List<Attachment>): String {
            val previews = attachments.mapNotNull { attachment ->
                (attachment.payload[ATTACHMENT_PREVIEW_KEY] as? JsonPrimitive)?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
            return (previews + text).filter { it.isNotEmpty() }.joinToString("\n")
        }
    }
}
