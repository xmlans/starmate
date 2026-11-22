package org.xmyun.starmate.feature.chat.interview

import java.time.Clock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.xmyun.starmate.core.model.ATTACHMENT_PREVIEW_KEY
import org.xmyun.starmate.core.model.Attachment
import org.xmyun.starmate.core.model.AttachmentTypes
import org.xmyun.starmate.core.model.ChatMessage
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.CompletedTurn
import org.xmyun.starmate.core.model.ConversationEngine
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.HeadlessResult
import org.xmyun.starmate.core.model.HeadlessTurn
import org.xmyun.starmate.core.model.MessageStatus
import org.xmyun.starmate.core.model.MessageStore
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.OnboardingState
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.feature.chat.common.ChatBackgroundScope

/**
 * 初识访谈的结束与落盘。
 *
 * 三种结束方式（模型调用 finish_onboarding、12 条回答后抽取、用户点跳过）都走 [finish]：
 * 状态先置 DONE（访谈提示词立即消失），资料暂存进 CompanionPrefs，再插入一条只带小结附件的助手消息。
 * 用户在卡片上点确认，或暂存期间又发了一条消息，才由 [confirm] 真正落盘。
 *
 * ConversationEngine 用 Provider 注入：引擎依赖 TurnListener / ToolProvider，而它们又依赖这里，直接注入会成环。
 */
@Singleton
class InterviewCoordinator @Inject constructor(
    private val prefs: CompanionPrefs,
    private val messages: MessageStore,
    private val engine: Provider<ConversationEngine>,
    private val applier: ProfileApplier,
    private val texts: InterviewTexts,
    private val background: ChatBackgroundScope,
    private val clock: Clock,
    private val diag: DiagLog,
) {
    private val mutex = Mutex()
    private val extractLock = Mutex()

    private val _extracting = MutableStateFlow(false)

    /** 正在用一次独立请求抽取资料。 */
    val extracting: StateFlow<Boolean> = _extracting.asStateFlow()

    /** 等待确认的资料；null 表示没有暂存（还没结束或已落盘）。 */
    val pendingProfile: Flow<OnboardingProfile?> =
        prefs.pendingOnboardingProfile.map { json -> json?.let(OnboardingProfile::parse) }

    suspend fun finish(profile: OnboardingProfile) {
        mutex.withLock { finishLocked(profile) }
    }

    private suspend fun finishLocked(profile: OnboardingProfile) {
        // 已经结束过（例如模型重复调用工具）时什么也不做
        if (prefs.onboardingState.first() == OnboardingState.DONE) return
        val normalized = profile.normalized()
        prefs.setOnboardingState(OnboardingState.DONE)
        prefs.setPendingOnboardingProfile(normalized.toJson())
        messages.insert(
            ChatMessage(
                role = Role.ASSISTANT,
                rawText = "",
                displayText = "",
                attachments = listOf(summaryAttachment(normalized, confirmed = false)),
                purpose = TurnPurpose.ONBOARDING,
                createdAt = clock.instant(),
                status = MessageStatus.COMPLETE,
            ),
        )
    }

    /** 右上角“跳过”：有回答时走抽取；一条回答都没有时直接以空资料结束，不调用模型。 */
    suspend fun skip() {
        if (prefs.onboardingState.first() != OnboardingState.INTERVIEWING) return
        val answers = interviewMessages().count { it.role == Role.USER && it.displayText.isNotBlank() }
        if (answers == 0) finish(OnboardingProfile.EMPTY) else extract(finishOnFailure = true)
    }

    fun skipAsync(): Job = background.scope.launch { runCatchingLogged("skip") { skip() } }

    /**
     * 从访谈原话里抽取资料。同一时间只跑一次；结果回来时访谈已经结束就丢弃。
     *
     * @param finishOnFailure 请求失败（断网、限流、预算）时是否仍以空资料结束访谈。
     *   用户点“跳过”时为 true（他想马上结束）；答满上限自动触发时先为 false，
     *   留在访谈里等下一轮再试，免得一次临时错误让访谈以空资料收场。
     */
    suspend fun extract(finishOnFailure: Boolean = true) {
        if (!extractLock.tryLock()) return
        _extracting.value = true
        try {
            if (prefs.onboardingState.first() != OnboardingState.INTERVIEWING) return
            val result = engine.get().runHeadless(
                HeadlessTurn(
                    purpose = TurnPurpose.ONBOARDING,
                    instruction = transcript(interviewMessages()),
                    role = ModelRole.CHAT,
                    systemOverride = texts.extractionPrompt(),
                    jsonOutput = true,
                ),
            )
            val profile = when (result) {
                is HeadlessResult.Success -> OnboardingProfile.parse(result.text)
                else -> {
                    diag.log(TAG, "extraction did not succeed: ${result.javaClass.simpleName}")
                    if (!finishOnFailure) return
                    OnboardingProfile.EMPTY
                }
            }
            mutex.withLock {
                if (prefs.onboardingState.first() == OnboardingState.INTERVIEWING) finishLocked(profile)
            }
        } finally {
            _extracting.value = false
            extractLock.unlock()
        }
    }

    /** InterviewTurnListener 在每轮结束后调用。 */
    suspend fun onTurnCompleted(turn: CompletedTurn) {
        // 暂存期间用户又说了一句话（访谈已结束，所以这轮用途不再是 ONBOARDING）：视为确认
        val finishedThisTurn = turn.toolCalls.any { it.name == FinishOnboardingTool.NAME }
        if (turn.userMessage != null && turn.purpose != TurnPurpose.ONBOARDING && !finishedThisTurn &&
            prefs.pendingOnboardingProfile.first() != null
        ) {
            confirm()
        }
        if (turn.purpose == TurnPurpose.ONBOARDING && prefs.onboardingState.first() == OnboardingState.INTERVIEWING) {
            val answers = interviewMessages().count { it.role == Role.USER }
            if (answers >= EXTRACT_AFTER_ANSWERS) {
                // 前几次失败先不结束，之后每轮再试；多试了 EXTRACT_RETRY_TURNS 轮还不行才以空资料结束
                extract(finishOnFailure = answers >= EXTRACT_AFTER_ANSWERS + EXTRACT_RETRY_TURNS)
            }
        }
    }

    /** 把暂存的资料落盘并清空暂存，同时把小结卡片标成已确认。 */
    suspend fun confirm() {
        mutex.withLock {
            val json = prefs.pendingOnboardingProfile.first() ?: return
            val profile = OnboardingProfile.parse(json)
            applier.apply(profile)
            prefs.setPendingOnboardingProfile(null)
            markSummaryConfirmed(profile)
        }
    }

    fun confirmAsync(): Job = background.scope.launch { runCatchingLogged("confirm") { confirm() } }

    /** 小结卡片上的逐条修改、删除，改的是暂存的资料。 */
    suspend fun updatePending(profile: OnboardingProfile) {
        mutex.withLock {
            if (prefs.pendingOnboardingProfile.first() == null) return
            prefs.setPendingOnboardingProfile(profile.normalized().toJson())
        }
    }

    fun updatePendingAsync(profile: OnboardingProfile): Job =
        background.scope.launch { runCatchingLogged("update") { updatePending(profile) } }

    private suspend fun interviewMessages(): List<ChatMessage> =
        messages.recent(HISTORY_LIMIT).filter { it.purpose == TurnPurpose.ONBOARDING }

    private fun transcript(history: List<ChatMessage>): String {
        val chinese = texts.chinese
        val userLabel = if (chinese) "用户" else "User"
        val herLabel = if (chinese) "她" else "Companion"
        val lines = history
            .filter { it.displayText.isNotBlank() && (it.role == Role.USER || it.role == Role.ASSISTANT) }
            .joinToString("\n") { message ->
                val label = if (message.role == Role.USER) userLabel else herLabel
                "$label: ${message.displayText.trim()}"
            }
        val header = if (chinese) "下面是初识聊天的原话：" else "Here is the first-meeting chat, word for word:"
        return "$header\n\n$lines"
    }

    private suspend fun markSummaryConfirmed(profile: OnboardingProfile) {
        val message = messages.recent(HISTORY_LIMIT)
            .lastOrNull { msg -> msg.attachments.any { it.type == AttachmentTypes.ONBOARDING_SUMMARY } }
            ?: return
        messages.update(
            message.copy(
                attachments = message.attachments.map { attachment ->
                    if (attachment.type == AttachmentTypes.ONBOARDING_SUMMARY) summaryAttachment(profile, confirmed = true) else attachment
                },
            ),
        )
    }

    private fun summaryAttachment(profile: OnboardingProfile, confirmed: Boolean): Attachment =
        Attachment(
            type = AttachmentTypes.ONBOARDING_SUMMARY,
            payload = buildJsonObject {
                put(ATTACHMENT_PREVIEW_KEY, texts.summaryPreview())
                put(PAYLOAD_PROFILE, json.encodeToJsonElement(OnboardingProfile.serializer(), profile.normalized()))
                put(PAYLOAD_CONFIRMED, confirmed)
            },
        )

    private suspend fun runCatchingLogged(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.log(TAG, "$what failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        /** ONBOARDING 用途的用户消息达到这个数时，在该轮结束后抽取。 */
        const val EXTRACT_AFTER_ANSWERS = 12

        /** 自动抽取失败后最多再试这么多轮。 */
        const val EXTRACT_RETRY_TURNS = 3
        private const val HISTORY_LIMIT = 500
        private const val TAG = "chat.onboarding"

        const val PAYLOAD_PROFILE = "profile"
        const val PAYLOAD_CONFIRMED = "confirmed"

        private val json = Json { explicitNulls = false; encodeDefaults = false }

        fun profileOf(attachment: Attachment): OnboardingProfile =
            (attachment.payload[PAYLOAD_PROFILE] as? JsonObject)?.let(OnboardingProfile::fromJson) ?: OnboardingProfile.EMPTY

        fun isConfirmed(attachment: Attachment): Boolean =
            (attachment.payload[PAYLOAD_CONFIRMED] as? JsonPrimitive)?.booleanOrNull ?: false
    }
}
