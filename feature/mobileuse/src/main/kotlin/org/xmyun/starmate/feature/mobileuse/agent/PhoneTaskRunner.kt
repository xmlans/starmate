package org.xmyun.starmate.feature.mobileuse.agent

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.xmyun.starmate.core.model.DiagLog
import org.xmyun.starmate.core.model.ImagePart
import org.xmyun.starmate.core.model.LlmClient
import org.xmyun.starmate.core.model.LlmEvent
import org.xmyun.starmate.core.model.LlmException
import org.xmyun.starmate.core.model.LlmMessage
import org.xmyun.starmate.core.model.LlmRequest
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ModelRouter
import org.xmyun.starmate.core.model.Role
import org.xmyun.starmate.core.model.SystemPrompt
import org.xmyun.starmate.core.model.TokenUsage
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UsageEntry
import org.xmyun.starmate.core.model.UsageLedger
import org.xmyun.starmate.feature.mobileuse.guard.Guard
import org.xmyun.starmate.feature.mobileuse.guard.Policy
import org.xmyun.starmate.feature.mobileuse.log.ActionLogger
import org.xmyun.starmate.feature.mobileuse.log.StepLog
import org.xmyun.starmate.feature.mobileuse.model.AgentAction
import org.xmyun.starmate.feature.mobileuse.model.Observation
import org.xmyun.starmate.feature.mobileuse.model.ScreenState
import org.xmyun.starmate.feature.mobileuse.model.Verdict
import org.xmyun.starmate.feature.mobileuse.overlay.TaskOverlay
import org.xmyun.starmate.feature.mobileuse.service.DeviceController
import org.xmyun.starmate.feature.mobileuse.service.Interruption
import org.xmyun.starmate.feature.mobileuse.tree.NodeIdentity
import org.xmyun.starmate.feature.mobileuse.tree.UiTreeSerializer

/**
 * 一次手机操作任务的循环：
 * 1. 经 [Guard.canObserve] 取屏幕（[observe]，唯一读屏的地方）。
 * 2. 把目标、步骤摘要、界面树发给 VISION 模型，向 UsageLedger 记一笔 MOBILE_USE。
 * 3. 解析动作；失败把错误反馈给模型，连续 3 次失败结束。
 * 4. 执行前重新取屏幕，经 [Guard.check] 得到放行后才交给 DeviceController（[act]，唯一执行动作的地方）。
 * 5. 等界面稳定，回到第 1 步。
 * 任务自己结束时（见 [TaskOutcome.returnsToStarmate]）先回到星伴，再收起悬浮条。
 * 每个实例只跑一次任务。
 */
class PhoneTaskRunner(
    private val router: ModelRouter,
    private val ledger: UsageLedger,
    private val device: DeviceController,
    private val overlay: TaskOverlay,
    private val guard: Guard,
    private val policies: PolicyProvider,
    private val logger: ActionLogger,
    private val prompts: PromptSource,
    private val clock: Clock,
    limits: TaskLimits = TaskLimits(),
    private val diag: DiagLog? = null,
    private val returner: AppReturner = AppReturner.None,
) {
    val limits: TaskLimits = limits.sanitized()
    private val texts = RunnerTexts(prompts.chinese)
    private val stopSignal = MutableStateFlow<StopReason?>(null)

    /** 立即结束任务：正在进行的模型调用会被取消，之后不再调用模型、不再操作设备。 */
    fun stop(reason: StopReason) {
        stopSignal.compareAndSet(null, reason)
    }

    suspend fun run(goal: String): TaskOutcome {
        val startedAt = clock.instant()
        val taskId = quietly { logger.startTask(goal.take(MAX_GOAL), startedAt) } ?: 0L
        diag?.log(TAG, "task started")
        val outcome = try {
            // 悬浮条（含“停止”）显示不出来就不开始
            overlay.showRunning { stop(StopReason.USER) }
            val result = supervise { loop(goal, startedAt, taskId) }
            // 趁悬浮条还在时回到星伴：这时星伴有可见窗口，系统允许它把自己的界面调到前台
            if (result.returnsToStarmate) withContext(NonCancellable) { quietly { returner.bringBack() } }
            result
        } finally {
            withContext(NonCancellable) { quietly { overlay.hide() } }
        }
        quietly { logger.finishTask(taskId, clock.instant(), outcome.code, texts.outcome(outcome, limits)) }
        diag?.log(TAG, "task ended: ${outcome.code}")
        return outcome
    }

    /** 文字结果，作为工具结果交回聊天。 */
    fun describe(outcome: TaskOutcome): String = texts.outcome(outcome, limits)

    // ---- 监督：停止按钮、熄屏、服务断开时立即取消循环 ----

    private suspend fun supervise(block: suspend () -> TaskOutcome): TaskOutcome = coroutineScope {
        val work = async { block() }
        val interrupts = launch {
            device.interruptions.collect { stop(it.toStopReason()) }
        }
        val stopper = launch {
            stopSignal.filterNotNull().first()
            work.cancel()
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            ensureActive()
            stopSignal.value?.let { TaskOutcome.Stopped(it) } ?: throw e
        } finally {
            interrupts.cancel()
            stopper.cancel()
        }
    }

    private fun Interruption.toStopReason(): StopReason = when (this) {
        Interruption.SCREEN_OFF -> StopReason.SCREEN_OFF
        Interruption.SERVICE_DISCONNECTED -> StopReason.SERVICE_DISCONNECTED
    }

    private fun stopOutcome(): TaskOutcome? = stopSignal.value?.let { TaskOutcome.Stopped(it) }

    // ---- 主循环 ----

    private suspend fun loop(goal: String, startedAt: Instant, taskId: Long): TaskOutcome {
        val client = router.chat(ModelRole.VISION) ?: return TaskOutcome.Failed(FailReason.NO_MODEL)
        val lookAvailable = client.supportsImages
        val parser = ActionParser(lookAvailable)
        val system = SystemPrompt(stable = prompts.systemPrompt(lookAvailable))
        val history = ArrayDeque<String>()
        var feedback: String? = null
        var steps = 0
        var tokens = 0L
        var parseFailures = 0
        var wantScreenshot = false

        while (true) {
            // 让出一次，停止、熄屏、断开的监听能及时生效
            yield()
            stopOutcome()?.let { return it }
            if (steps >= limits.maxSteps) return TaskOutcome.LimitReached(LimitKind.STEPS)
            val remaining = limits.maxDuration - elapsedSince(startedAt)
            if (remaining <= Duration.ZERO) return TaskOutcome.LimitReached(LimitKind.TIME)

            val policy = policies.current()
            val observation = observe(policy, withScreenshot = wantScreenshot && lookAvailable)
            wantScreenshot = false
            if (observation is Observation.Halt) {
                logStep(taskId, null, "observe", VERDICT_HALT, texts.finding(observation.finding))
                return TaskOutcome.Halted(observation.finding)
            }

            val request = buildRequest(system, goal, policy, history, feedback, observation)
            feedback = null
            stopOutcome()?.let { return it }
            steps++
            val reply = try {
                withTimeoutOrNull(remaining.toMillis().coerceAtLeast(1)) { callModel(client, request) }
                    ?: return TaskOutcome.LimitReached(LimitKind.TIME)
            } catch (e: LlmException) {
                diag?.log(TAG, "model error: ${e.kind}")
                return TaskOutcome.Failed(FailReason.MODEL_ERROR, e.kind)
            }
            tokens += reply.usage.inputTokens.toLong() + reply.usage.outputTokens.toLong()
            recordUsage(client, reply.usage)
            if (tokens >= limits.maxTokens) return TaskOutcome.LimitReached(LimitKind.TOKENS)
            yield()
            stopOutcome()?.let { return it }

            val action = when (val parsed = parser.parse(reply.text)) {
                is ParseResult.Error -> {
                    parseFailures++
                    if (parseFailures >= limits.maxParseFailures) return TaskOutcome.Failed(FailReason.PARSE_FAILURES)
                    feedback = texts.parseError(parsed.message)
                    remember(history, "$steps. (invalid reply)")
                    continue
                }
                is ParseResult.Ok -> {
                    parseFailures = 0
                    parsed.action
                }
            }

            val result: String = when (action) {
                is AgentAction.Done -> {
                    logStep(taskId, observation, describe(action), VERDICT_DONE, null)
                    return TaskOutcome.Done(action.summary)
                }
                is AgentAction.AskUser -> {
                    val yes = confirm(texts.askUser(action.question))
                    logStep(taskId, observation, describe(action), if (yes) "USER_YES" else "USER_NO", null)
                    texts.userAnswered(yes)
                }
                is AgentAction.Wait -> {
                    delay(action.seconds * 1_000L)
                    texts.waited(action.seconds)
                }
                is AgentAction.Look -> {
                    if (lookAvailable && observation is Observation.Visible) {
                        wantScreenshot = true
                        texts.lookQueued()
                    } else {
                        texts.lookUnavailable()
                    }
                }
                else -> when (val r = act(action, observation, taskId)) {
                    is ActResult.End -> return r.outcome
                    is ActResult.Next -> r.feedback
                }
            }
            feedback = result
            remember(history, "$steps. ${describe(action)} → $result")
        }
    }

    /**
     * 检查点 A：读屏幕的唯一位置。
     * 读界面树和截图都要 Guard 发的一次性许可，canObserve 不通过时 readScreen 不会被调用。
     */
    private suspend fun observe(policy: Policy, withScreenshot: Boolean): Observation =
        guard.canObserve(device.foreground(), policy) { permit -> device.readScreen(permit, withScreenshot) }

    private sealed interface ActResult {
        data class Next(val feedback: String) : ActResult
        data class End(val outcome: TaskOutcome) : ActResult
    }

    /**
     * 执行一个设备动作。每次都先重新取屏幕（上一步可能已经把界面带到收银台或别的应用），
     * 确认目标还是模型看到的那个元素，再过守卫。
     */
    private suspend fun act(action: AgentAction, seen: Observation, taskId: Long): ActResult {
        var confirmation: String? = null
        while (true) {
            stopOutcome()?.let { return ActResult.End(it) }
            val policy = policies.current()
            val fresh = observe(policy, withScreenshot = false)
            val screen = when (fresh) {
                is Observation.Halt -> {
                    logStep(taskId, null, describe(action), VERDICT_HALT, texts.finding(fresh.finding))
                    return ActResult.End(TaskOutcome.Halted(fresh.finding))
                }
                is Observation.Blind -> ScreenState.headerOnly(fresh.foreground)
                is Observation.Visible -> fresh.screen
            }
            if (action is AgentAction.Targeted) {
                val before = (seen as? Observation.Visible)
                    ?.let { UiTreeSerializer.visibleNodes(it.screen.nodes).getOrNull(action.index) }
                    ?: return ActResult.Next(texts.noSuchIndex(action.index))
                val now = UiTreeSerializer.visibleNodes(screen.nodes).getOrNull(action.index)
                if (!NodeIdentity.same(before, now)) return ActResult.Next(texts.screenChanged())
                if (action is AgentAction.InputText && now?.editable != true) {
                    return ActResult.Next(texts.notEditable(action.index))
                }
            }

            // 检查点 B：执行动作的唯一位置。只有 Verdict.Allow 里的 ApprovedAction 能交给 DeviceController。
            when (val verdict = guard.check(action, screen, policy, confirmation)) {
                is Verdict.Halt -> {
                    logStep(taskId, screen, describe(action), VERDICT_HALT, texts.finding(verdict.finding))
                    return ActResult.End(TaskOutcome.Halted(verdict.finding))
                }
                is Verdict.NeedsUserConfirm -> {
                    logStep(taskId, screen, describe(action), VERDICT_CONFIRM, texts.finding(verdict.finding))
                    // 用户同意后守卫仍要求确认：不再追问，按未同意处理，避免死循环
                    if (confirmation != null) return ActResult.Next(texts.userDeclined())
                    val target = (action as? AgentAction.Targeted)
                        ?.let { UiTreeSerializer.visibleNodes(screen.nodes).getOrNull(it.index) }
                    val label = target?.text?.takeIf { it.isNotBlank() }
                        ?: target?.contentDescription?.takeIf { it.isNotBlank() }
                        ?: target?.descendantText
                    val yes = confirm(texts.confirmTap(verdict.finding.subject ?: "?", label))
                    logStep(taskId, screen, describe(action), if (yes) "USER_APPROVED" else "USER_DECLINED", null)
                    if (!yes) return ActResult.Next(texts.userDeclined())
                    confirmation = verdict.confirmationKey
                    // 回到循环开头：重新取屏幕、重新核对目标、重新过守卫
                }
                is Verdict.Allow -> {
                    stopOutcome()?.let { return ActResult.End(it) }
                    val ok = device.perform(verdict.approved)
                    logStep(taskId, screen, describe(action), if (ok) VERDICT_ALLOW else "ALLOW_FAILED", null)
                    device.awaitSettled(limits.settleTimeoutMs)
                    return ActResult.Next(if (ok) texts.ok() else texts.performFailed())
                }
            }
        }
    }

    private suspend fun confirm(message: String): Boolean =
        withTimeoutOrNull(limits.confirmTimeoutMs) { overlay.confirm(message) } ?: false

    // ---- 模型 ----

    private data class Reply(val text: String, val usage: TokenUsage)

    private suspend fun callModel(client: LlmClient, request: LlmRequest): Reply {
        val text = StringBuilder()
        var usage = TokenUsage()
        client.stream(request).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> text.append(event.text)
                is LlmEvent.Completed -> usage += event.usage
                // 没给工具，模型仍返回的工具调用直接忽略
                is LlmEvent.ToolCallRequested -> Unit
            }
        }
        return Reply(text.toString(), usage)
    }

    private suspend fun recordUsage(client: LlmClient, usage: TokenUsage) {
        quietly {
            ledger.record(
                UsageEntry(
                    purpose = TurnPurpose.MOBILE_USE,
                    model = client.modelId,
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    cachedInputTokens = usage.cachedInputTokens,
                    at = clock.instant(),
                ),
            )
        }
    }

    private fun buildRequest(
        system: SystemPrompt,
        goal: String,
        policy: Policy,
        history: List<String>,
        feedback: String?,
        observation: Observation,
    ): LlmRequest {
        val sb = StringBuilder()
        sb.append(texts.goalHeader()).append(UiTreeSerializer.clean(goal).take(MAX_GOAL)).append('\n')
        val apps = policy.allowlist.sorted().joinToString("; ") { pkg ->
            val label = policy.labelOf(pkg)?.let { UiTreeSerializer.truncate(UiTreeSerializer.clean(it)) } ?: "?"
            "$label ($pkg)"
        }
        sb.append(texts.allowedAppsHeader()).append(apps).append('\n')
        if (history.isNotEmpty()) {
            sb.append(texts.historyHeader()).append('\n')
            history.forEach { sb.append(it).append('\n') }
        }
        feedback?.let { sb.append(texts.lastResultHeader()).append(it).append('\n') }
        val images = mutableListOf<ImagePart>()
        when (observation) {
            is Observation.Visible -> {
                sb.append(texts.screenHeader()).append('\n')
                sb.append(UiTreeSerializer.serialize(observation.screen, texts.chinese)).append('\n')
                observation.screenshotJpegBase64?.let {
                    images += ImagePart("image/jpeg", it)
                    sb.append(texts.screenshotAttached()).append('\n')
                }
            }
            is Observation.Blind -> sb.append(
                texts.blindScreen(texts.finding(observation.finding), observation.allowed.joinToString(", ") { it.wireName }),
            ).append('\n')
            is Observation.Halt -> Unit
        }
        sb.append(texts.replyInstruction())
        return LlmRequest(
            system = system,
            messages = listOf(LlmMessage(role = Role.USER, text = sb.toString(), images = images)),
            maxOutputTokens = MAX_OUTPUT_TOKENS,
            temperature = 0.0,
            jsonOutput = true,
        )
    }

    // ---- 杂项 ----

    private fun elapsedSince(start: Instant): Duration = Duration.between(start, clock.instant())

    private fun remember(history: ArrayDeque<String>, line: String) {
        history.addLast(line)
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    private fun describe(action: AgentAction): String {
        val why = action.why.takeIf { it.isNotBlank() }?.let { " (${UiTreeSerializer.truncate(UiTreeSerializer.clean(it))})" }.orEmpty()
        val body = when (action) {
            is AgentAction.Tap -> "tap [${action.index}]"
            is AgentAction.LongPress -> "long_press [${action.index}]"
            is AgentAction.InputText -> "input_text [${action.index}] \"${UiTreeSerializer.truncate(UiTreeSerializer.clean(action.text))}\""
            is AgentAction.Scroll -> "scroll [${action.index}] ${action.direction.name.lowercase()}"
            is AgentAction.Back -> "back"
            is AgentAction.Home -> "home"
            is AgentAction.OpenApp -> "open_app ${UiTreeSerializer.truncate(UiTreeSerializer.clean(action.packageName))}"
            is AgentAction.Wait -> "wait ${action.seconds}s"
            is AgentAction.Look -> "look"
            is AgentAction.AskUser -> "ask_user"
            is AgentAction.Done -> "done"
        }
        return body + why
    }

    private suspend fun logStep(
        taskId: Long,
        screenOrObservation: Any?,
        description: String,
        verdict: String,
        detail: String?,
    ) {
        val (pkg, label) = when (screenOrObservation) {
            is ScreenState -> screenOrObservation.packageName to screenOrObservation.appLabel
            is Observation.Visible -> screenOrObservation.screen.packageName to screenOrObservation.screen.appLabel
            is Observation.Blind -> screenOrObservation.foreground.packageName to screenOrObservation.foreground.appLabel
            else -> null to null
        }
        quietly { logger.logStep(taskId, StepLog(clock.instant(), pkg, label, description, verdict, detail)) }
    }

    /** 日志、记账失败不影响任务；取消照常抛出。 */
    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        diag?.log(TAG, "side task failed: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "mobileuse"
        private const val MAX_GOAL = 1_000
        private const val MAX_HISTORY = 12
        private const val MAX_OUTPUT_TOKENS = 512
        const val VERDICT_ALLOW = "ALLOW"
        const val VERDICT_CONFIRM = "CONFIRM"
        const val VERDICT_HALT = "HALT"
        const val VERDICT_DONE = "DONE"
    }
}
