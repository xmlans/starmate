package org.xmyun.starmate.feature.mobileuse

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import org.xmyun.starmate.core.model.DailyUsage
import org.xmyun.starmate.core.model.EmbeddingClient
import org.xmyun.starmate.core.model.EndpointConfig
import org.xmyun.starmate.core.model.LlmClient
import org.xmyun.starmate.core.model.LlmEvent
import org.xmyun.starmate.core.model.LlmRequest
import org.xmyun.starmate.core.model.ModelRole
import org.xmyun.starmate.core.model.ModelRouter
import org.xmyun.starmate.core.model.TokenUsage
import org.xmyun.starmate.core.model.TurnPurpose
import org.xmyun.starmate.core.model.UsageEntry
import org.xmyun.starmate.core.model.UsageLedger
import org.xmyun.starmate.feature.mobileuse.agent.PolicyProvider
import org.xmyun.starmate.feature.mobileuse.agent.PromptSource
import org.xmyun.starmate.feature.mobileuse.guard.ApprovedAction
import org.xmyun.starmate.feature.mobileuse.guard.BlockList
import org.xmyun.starmate.feature.mobileuse.guard.ObservePermit
import org.xmyun.starmate.feature.mobileuse.guard.Policy
import org.xmyun.starmate.feature.mobileuse.log.ActionLogger
import org.xmyun.starmate.feature.mobileuse.log.StepLog
import org.xmyun.starmate.feature.mobileuse.model.Foreground
import org.xmyun.starmate.feature.mobileuse.model.NodeBounds
import org.xmyun.starmate.feature.mobileuse.model.RawObservation
import org.xmyun.starmate.feature.mobileuse.model.ScreenState
import org.xmyun.starmate.feature.mobileuse.model.UiNode
import org.xmyun.starmate.feature.mobileuse.overlay.TaskOverlay
import org.xmyun.starmate.feature.mobileuse.service.DeviceController
import org.xmyun.starmate.feature.mobileuse.service.Interruption

const val OWN = "org.xmyun.starmate"
const val NOTES = "com.example.notes"
const val CHAT_APP = "com.example.chat"
const val SMS_APP = "com.example.sms"
const val LAUNCHER = "com.example.launcher"

/** 直接读源码里的黑名单文件（单元测试的工作目录是模块目录）。 */
val realBlockList: BlockList by lazy {
    BlockList.parse(File("src/main/res/raw/blocked_packages.txt").readText(Charsets.UTF_8))
}

val LABELS = mapOf(
    NOTES to "Notes",
    CHAT_APP to "Chat",
    SMS_APP to "Messenger X",
    LAUNCHER to "Home",
    "com.eg.android.AlipayGphone" to "支付宝",
    "com.android.settings" to "Settings",
    "com.android.systemui" to "System UI",
    "com.google.android.apps.authenticator2" to "Google Authenticator",
    OWN to "星伴",
)

fun policy(
    allowlist: Set<String> = setOf(NOTES, CHAT_APP),
    defaultSms: String? = SMS_APP,
    labels: Map<String, String> = LABELS,
) = Policy(
    allowlist = allowlist,
    blockList = realBlockList,
    ownPackage = OWN,
    defaultSmsPackage = defaultSms,
    launcherPackages = setOf(LAUNCHER),
    labelOf = { labels[it] },
)

fun node(
    id: Int,
    text: String? = null,
    desc: String? = null,
    clickable: Boolean = false,
    editable: Boolean = false,
    scrollable: Boolean = false,
    password: Boolean = false,
    className: String = if (editable) "android.widget.EditText" else "android.widget.TextView",
    hint: String? = null,
    viewId: String? = null,
    bounds: NodeBounds? = NodeBounds(0, id * 10, 100, id * 10 + 10),
) = UiNode(
    id = id,
    className = className,
    text = text,
    contentDescription = desc,
    hint = hint,
    viewId = viewId,
    clickable = clickable,
    editable = editable,
    scrollable = scrollable,
    isPassword = password,
    packageName = null,
    bounds = bounds,
)

fun screen(
    pkg: String? = NOTES,
    label: String? = LABELS[pkg],
    nodes: List<UiNode> = listOf(node(0, "Hello"), node(1, "下一页", clickable = true)),
    passwordKeyboard: Boolean = false,
    others: Set<String> = emptySet(),
    snapshotId: Long = 1,
) = ScreenState(
    packageName = pkg,
    appLabel = label,
    nodes = nodes,
    passwordKeyboardShown = passwordKeyboard,
    otherAppPackages = others,
    snapshotId = snapshotId,
)

class FixedClock(private val now: Instant = Instant.parse("2026-11-12T10:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
}

/** 每次调用按顺序返回一条回复；用完后重复最后一条。 */
class FakeLlmClient(
    private val replies: List<String>,
    private val usagePerCall: TokenUsage = TokenUsage(inputTokens = 100, outputTokens = 20),
    override val supportsImages: Boolean = false,
) : LlmClient {
    override val modelId: String = "fake-vision"
    override val supportsTools: Boolean = true
    val requests = mutableListOf<LlmRequest>()

    /** 每次调用开始时回调（用来模拟“调用期间用户点了停止”）。 */
    var onCall: (Int) -> Unit = {}

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        requests += request
        onCall(requests.size)
        val text = replies.getOrElse(requests.size - 1) { replies.last() }
        emit(LlmEvent.TextDelta(text))
        emit(LlmEvent.Completed(usagePerCall, org.xmyun.starmate.core.model.StopReason.END))
    }
}

class FakeRouter(private val client: LlmClient?) : ModelRouter {
    val roles = mutableListOf<ModelRole>()
    override suspend fun chat(role: ModelRole): LlmClient? {
        roles += role
        return client
    }
    override suspend fun embedding(): EmbeddingClient? = null
    override suspend fun endpoint(role: ModelRole): EndpointConfig? = null
    override suspend fun markToolsUnsupported(role: ModelRole) = Unit
}

class FakeLedger : UsageLedger {
    val entries = mutableListOf<UsageEntry>()
    override suspend fun record(entry: UsageEntry) {
        entries += entry
    }
    override suspend fun usedToday(purpose: TurnPurpose): Int = 0
    override suspend fun allow(purpose: TurnPurpose): Boolean = true
    override fun observeDaily(days: Int): Flow<List<DailyUsage>> = emptyFlow()
}

/**
 * 假设备。屏幕由 [screenFor] 根据已执行的动作次数决定；记录所有读取和执行。
 * 和真实实现一样，readScreen 只认有效许可且包名一致，perform 只认未用过的 ApprovedAction。
 */
class FakeDevice(var screenFor: (performedCount: Int) -> ScreenState) : DeviceController {
    val performed = mutableListOf<ApprovedAction>()
    var reads = 0
    val interruptionFlow = MutableSharedFlow<Interruption>(extraBufferCapacity = 4)
    private var snapshot = 0L

    override val isConnected: Boolean = true
    override val interruptions: Flow<Interruption> = interruptionFlow

    override suspend fun foreground(): Foreground? = screenFor(performed.size).foreground

    override suspend fun readScreen(permit: ObservePermit, withScreenshot: Boolean): RawObservation? {
        if (!permit.isValid) return null
        val s = screenFor(performed.size)
        if (s.packageName != permit.packageName) return null
        reads++
        snapshot++
        return RawObservation(s.copy(snapshotId = snapshot), if (withScreenshot) "BASE64" else null)
    }

    override suspend fun perform(approved: ApprovedAction): Boolean {
        if (!approved.consume()) return false
        performed += approved
        return true
    }

    override suspend fun awaitSettled(timeoutMs: Long) = Unit
}

class FakeOverlay(private val answers: ArrayDeque<Boolean> = ArrayDeque()) : TaskOverlay {
    var shown = false
    var hidden = false
    val questions = mutableListOf<String>()
    var onStop: (() -> Unit)? = null

    override suspend fun showRunning(onStop: () -> Unit) {
        shown = true
        this.onStop = onStop
    }

    override suspend fun hide() {
        hidden = true
    }

    override suspend fun confirm(message: String): Boolean {
        questions += message
        return answers.removeFirstOrNull() ?: false
    }
}

class FakeLogger : ActionLogger {
    val steps = mutableListOf<StepLog>()
    var finishedOutcome: String? = null
    override suspend fun startTask(goal: String, at: Instant): Long = 1
    override suspend fun logStep(taskId: Long, step: StepLog) {
        steps += step
    }
    override suspend fun finishTask(taskId: Long, at: Instant, outcome: String, detail: String) {
        finishedOutcome = outcome
    }
}

class FakePrompts(override val chinese: Boolean = true) : PromptSource {
    override fun systemPrompt(lookAvailable: Boolean): String = "SYSTEM look=$lookAvailable"
}

class FixedPolicies(private val p: Policy = policy()) : PolicyProvider {
    override suspend fun current(): Policy = p
}
