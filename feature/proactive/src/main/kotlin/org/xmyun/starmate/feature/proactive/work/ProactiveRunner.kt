package org.xmyun.starmate.feature.proactive.work

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.xmyun.starmate.core.model.AmbientSource
import org.xmyun.starmate.core.model.CompanionPrefs
import org.xmyun.starmate.core.model.ConversationEngine
import org.xmyun.starmate.core.model.DaySummarySource
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.HeadlessResult
import org.xmyun.starmate.core.model.HeadlessTurn
import org.xmyun.starmate.core.model.MessageStore
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UsageLedger
import org.xmyun.starmate.core.model.previewText
import org.xmyun.starmate.feature.proactive.gate.CheckInCondition
import org.xmyun.starmate.feature.proactive.gate.DenyReason
import org.xmyun.starmate.feature.proactive.gate.GateInput
import org.xmyun.starmate.feature.proactive.gate.GateResult
import org.xmyun.starmate.feature.proactive.gate.ProactiveGate
import org.xmyun.starmate.feature.proactive.model.Care
import org.xmyun.starmate.feature.proactive.model.CareStatus
import org.xmyun.starmate.feature.proactive.model.LogOutcome
import org.xmyun.starmate.feature.proactive.model.LogReasons
import org.xmyun.starmate.feature.proactive.model.LogSource
import org.xmyun.starmate.feature.proactive.model.SendLogEntry
import org.xmyun.starmate.feature.proactive.notify.ProactiveNotifier
import org.xmyun.starmate.feature.proactive.platform.NetworkStatus
import org.xmyun.starmate.feature.proactive.platform.ProactiveTexts
import org.xmyun.starmate.feature.proactive.platform.RandomSource
import org.xmyun.starmate.feature.proactive.prefs.ProactivePrefs
import org.xmyun.starmate.feature.proactive.schedule.CareDeferral
import org.xmyun.starmate.feature.proactive.schedule.CareRescheduler
import org.xmyun.starmate.feature.proactive.schedule.CareScheduler
import org.xmyun.starmate.feature.proactive.schedule.Deferral
import org.xmyun.starmate.feature.proactive.store.CareStore
import org.xmyun.starmate.feature.proactive.store.SendLogStore

enum class RunOutcome { SENT, BLOCKED, DECLINED, FAILED, GAVE_UP, IGNORED, PAUSED }

/**
 * 两个 Worker 共用的主流程：过闸门 → 调模型 → 解析决定 → 发送。
 * 闸门不通过绝不调用模型；发送只有 [generateAndSend] 一条路径，且只在闸门通过之后。
 */
@Singleton
class ProactiveRunner @Inject constructor(
    private val prefs: ProactivePrefs,
    private val companionPrefs: CompanionPrefs,
    private val messages: MessageStore,
    private val ledger: UsageLedger,
    private val engine: ConversationEngine,
    private val cares: CareStore,
    private val logs: SendLogStore,
    private val scheduler: CareScheduler,
    private val rescheduler: CareRescheduler,
    private val notifier: ProactiveNotifier,
    private val network: NetworkStatus,
    ambientSources: Set<@JvmSuppressWildcards AmbientSource>,
    private val daySummary: Optional<DaySummarySource>,
    private val texts: ProactiveTexts,
    private val random: RandomSource,
    private val clock: Clock,
    private val diag: DiagLog,
) {
    private val ambientSources = ambientSources.sortedBy { it.id }

    // 两个 Worker 可能同时醒来，串行执行，避免同一时刻发出两条
    private val mutex = Mutex()

    suspend fun runCare(careId: Long): RunOutcome = mutex.withLock { doRunCare(careId) }

    suspend fun runCheckIn(): RunOutcome = mutex.withLock { doRunCheckIn() }

    private suspend fun doRunCare(careId: Long): RunOutcome {
        val care = cares.byId(careId)
        if (care == null || care.status != CareStatus.PENDING) return RunOutcome.IGNORED
        val now = clock.instant()
        val gate = ProactiveGate.evaluate(gateInput(now))
        // 功能被关掉：挂起。不登记下一次闹钟、不写记录，重新启用时由 CareRescheduler 统一登记或放弃
        if (gate is GateResult.Deny && gate.reason == DenyReason.DISABLED) return RunOutcome.PAUSED
        if (CareDeferral.isExpired(care.scheduledFor, now)) {
            rescheduler.expire(care)
            return RunOutcome.GAVE_UP
        }
        if (gate is GateResult.Deny) {
            log(LogSource.CARE, care, LogOutcome.BLOCKED, gate.reason.name)
            return defer(care, gate.reason, now)
        }
        return generateAndSend(LogSource.CARE, care.copy(attempts = care.attempts + 1), now)
    }

    private suspend fun doRunCheckIn(): RunOutcome {
        val now = clock.instant()
        val input = gateInput(now)
        val gate = ProactiveGate.evaluate(input)
        if (gate is GateResult.Deny) {
            // 关闭状态下不记：用户自己关的，记下来只会刷屏
            if (gate.reason != DenyReason.DISABLED) log(LogSource.CHECK_IN, null, LogOutcome.BLOCKED, gate.reason.name)
            return RunOutcome.BLOCKED
        }
        if (!CheckInCondition.isIdleEnough(now, input.lastChatMessageAt, input.clinginess)) {
            val reason = if (input.lastChatMessageAt == null) LogReasons.NO_HISTORY else LogReasons.NOT_IDLE
            log(LogSource.CHECK_IN, null, LogOutcome.BLOCKED, reason)
            return RunOutcome.BLOCKED
        }
        return generateAndSend(LogSource.CHECK_IN, null, now)
    }

    private suspend fun generateAndSend(source: LogSource, care: Care?, startedAt: Instant): RunOutcome {
        val instruction = buildInstruction(care, startedAt)
        val result = engine.runHeadless(
            HeadlessTurn(
                purpose = TurnPurpose.PROACTIVE,
                instruction = instruction,
                includeRecentMessages = RECENT_MESSAGES,
                jsonOutput = true,
            ),
        )
        val text = when (result) {
            is HeadlessResult.BudgetExceeded -> {
                log(source, care, LogOutcome.BLOCKED, DenyReason.BUDGET.name)
                return care?.let { defer(it, DenyReason.BUDGET, clock.instant()) } ?: RunOutcome.BLOCKED
            }
            is HeadlessResult.Failed -> {
                log(source, care, LogOutcome.FAILED, result.error.kind.name)
                care?.let { defer(it, null, clock.instant()) }
                return RunOutcome.FAILED
            }
            is HeadlessResult.Success -> result.text
        }

        val decision = DecisionParser.parse(text)
        if (decision !is Decision.Send) {
            val reason = if (decision is Decision.Skip) LogReasons.MODEL_DECLINED else LogReasons.UNPARSEABLE
            log(source, care, LogOutcome.DECLINED, reason)
            care?.let { cares.update(it.copy(status = CareStatus.DECLINED, updatedAt = clock.instant())) }
            return RunOutcome.DECLINED
        }

        // 生成期间用户开始聊天了：这时插一条主动消息不合适
        val lastChat = messages.lastMessageAt(null)
        if (engine.isBusy.value || (lastChat != null && lastChat.isAfter(startedAt))) {
            log(source, care, LogOutcome.BLOCKED, LogReasons.CHAT_STARTED)
            return care?.let { defer(it, DenyReason.CHATTING, clock.instant()) } ?: RunOutcome.BLOCKED
        }

        val message = try {
            engine.deliver(decision.text, TurnPurpose.PROACTIVE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.log(TAG, "deliver failed: ${e.javaClass.simpleName}")
            log(source, care, LogOutcome.FAILED, LogReasons.DELIVER_ERROR)
            care?.let { defer(it, null, clock.instant()) }
            return RunOutcome.FAILED
        }
        log(source, care, LogOutcome.SENT, null, text = message.previewText())
        care?.let { cares.update(it.copy(status = CareStatus.SENT, updatedAt = clock.instant())) }
        try {
            notifier.showMessage(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diag.log(TAG, "notify failed: ${e.javaClass.simpleName}")
        }
        return RunOutcome.SENT
    }

    private suspend fun defer(care: Care, reason: DenyReason?, now: Instant): RunOutcome {
        val quiet = companionPrefs.quietHours.first()
        return when (val deferral = CareDeferral.decide(reason, care.scheduledFor, now, clock.zone, quiet, random)) {
            is Deferral.RetryAt -> {
                cares.update(care.copy(nextAttemptAt = deferral.at, updatedAt = now))
                scheduler.schedule(care.id, deferral.at)
                if (reason == null) RunOutcome.FAILED else RunOutcome.BLOCKED
            }
            Deferral.GiveUp -> {
                rescheduler.expire(care)
                RunOutcome.GAVE_UP
            }
        }
    }

    private suspend fun gateInput(now: Instant): GateInput {
        val zone = clock.zone
        val startOfDay = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val lastUser = messages.lastMessageAt(Role.USER)
        return GateInput(
            now = now,
            zone = zone,
            enabled = prefs.enabled.first(),
            clinginess = companionPrefs.clinginess.first(),
            networkAvailable = network.isOnline(),
            quietHours = companionPrefs.quietHours.first(),
            sentToday = logs.countSentSince(startOfDay),
            lastProactiveAt = logs.lastSentAt(),
            proactiveSinceLastUserMessage = logs.countSentAfter(lastUser),
            lastChatMessageAt = messages.lastMessageAt(null),
            engineBusy = engine.isBusy.value,
            budgetAllows = ledger.allow(TurnPurpose.PROACTIVE),
        )
    }

    private suspend fun buildInstruction(care: Care?, now: Instant): String {
        val lastChat = messages.lastMessageAt(null)
        val context = InstructionContext(
            now = now.atZone(clock.zone),
            ambient = ambientSnapshots(),
            sinceLastChat = lastChat?.let { Duration.between(it, now) },
            summary = recentGist(now),
            care = care,
        )
        return InstructionBuilder.build(texts.instructionTemplate(), texts.chinese, context)
    }

    // 每个来源最多等 2 秒，超时或出错的忽略
    private suspend fun ambientSnapshots(): List<String> = coroutineScope {
        ambientSources.map { source ->
            async {
                try {
                    withTimeoutOrNull(AMBIENT_TIMEOUT_MS) { source.snapshot() }?.trim()?.takeIf { it.isNotEmpty() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    // 只读已有梗概（refresh = false），不额外调用模型
    private suspend fun recentGist(now: Instant): String? {
        val source = daySummary.orElse(null) ?: return null
        return try {
            withTimeoutOrNull(SUMMARY_TIMEOUT_MS) {
                source.daySummary(now.minus(SUMMARY_WINDOW), now, refresh = false)
            }?.gist?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun log(source: LogSource, care: Care?, outcome: LogOutcome, reason: String?, text: String? = null) {
        logs.add(
            SendLogEntry(
                at = clock.instant(),
                source = source,
                careId = care?.id,
                topic = care?.topic,
                outcome = outcome,
                reason = reason,
                text = text,
            ),
        )
        diag.log(TAG, "${source.name} ${outcome.name}${reason?.let { " $it" } ?: ""}")
    }

    companion object {
        private const val TAG = "proactive"
        const val RECENT_MESSAGES = 12
        const val AMBIENT_TIMEOUT_MS = 2_000L
        private const val SUMMARY_TIMEOUT_MS = 3_000L
        private val SUMMARY_WINDOW: Duration = Duration.ofDays(2)
    }
}
