package org.xmyun.starmate.feature.mobileuse.guard

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import org.xmyun.starmate.feature.mobileuse.model.ActionKind
import org.xmyun.starmate.feature.mobileuse.model.AgentAction
import org.xmyun.starmate.feature.mobileuse.model.Finding
import org.xmyun.starmate.feature.mobileuse.model.FindingCode
import org.xmyun.starmate.feature.mobileuse.model.Foreground
import org.xmyun.starmate.feature.mobileuse.model.Observation
import org.xmyun.starmate.feature.mobileuse.model.RawObservation
import org.xmyun.starmate.feature.mobileuse.model.ScreenState
import org.xmyun.starmate.feature.mobileuse.model.UiNode
import org.xmyun.starmate.feature.mobileuse.model.Verdict
import org.xmyun.starmate.feature.mobileuse.tree.NodeIdentity
import org.xmyun.starmate.feature.mobileuse.tree.UiTreeSerializer

/**
 * 只有本文件能拿到这把钥匙，所以 [ObservePermit] 和 [ApprovedAction] 只能由 [Guard] 创建。
 * DeviceController 读界面要 ObservePermit，执行动作要 ApprovedAction，这样就没有绕过守卫的路。
 */
private val GUARD_KEY = Any()

/** 读一次界面树（和截图）的许可。只在 [Guard.canObserve] 的读取回调期间有效，回调结束即作废。 */
class ObservePermit internal constructor(key: Any, val packageName: String) {
    init {
        check(key === GUARD_KEY) { "ObservePermit can only be created by Guard" }
    }

    private val valid = AtomicBoolean(true)
    val isValid: Boolean get() = valid.get()

    internal fun revoke() = valid.set(false)
}

/**
 * 守卫放行的一个动作，只能执行一次。
 * @param packageName 放行时的前台包名；设备层执行前会再核对一次前台还是它。
 * @param snapshotId 放行时检查的那份屏幕快照；设备层只对这份快照里的节点执行。
 * @param target 动作作用的节点（守卫检查的正是它）。
 */
class ApprovedAction internal constructor(
    key: Any,
    val action: AgentAction,
    val packageName: String,
    val snapshotId: Long,
    val target: UiNode?,
) {
    init {
        check(key === GUARD_KEY) { "ApprovedAction can only be created by Guard" }
    }

    private val used = AtomicBoolean(false)

    /** 第一次调用返回 true，之后都是 false。设备层执行前必须调用。 */
    fun consume(): Boolean = used.compareAndSet(false, true)
}

/**
 * 守卫。纯 Kotlin，只看 [ScreenState]、[Foreground] 和 [Policy]，不看模型说了什么。
 * 拿不准时（包名、应用名、界面树取不到）一律拦截。规则编号见设计文档 5.8。
 */
class Guard @Inject constructor() {

    private sealed interface Gate {
        data object Open : Gate
        data class BlindOnly(val finding: Finding, val allowed: Set<ActionKind>) : Gate
        data class Stop(val finding: Finding) : Gate
    }

    /**
     * 能否观察当前屏幕（G10 的入口）。
     * 1. 只凭前台包名和应用名判断，不通过就不读界面树、不截图，[read] 根本不会被调用。
     * 2. 通过后发一张一次性许可给 [read]，读回来的内容再做 G3 等内容检查；不通过的内容不会返回给调用方。
     */
    suspend fun canObserve(
        foreground: Foreground?,
        policy: Policy,
        read: suspend (ObservePermit) -> RawObservation?,
    ): Observation {
        val fg = foreground ?: return Observation.Halt(Finding(FindingCode.NO_FOREGROUND))
        when (val gate = packageGate(fg.packageName, fg.appLabel, fg.otherAppPackages, policy)) {
            is Gate.Stop -> return Observation.Halt(gate.finding)
            is Gate.BlindOnly -> return Observation.Blind(fg, gate.finding, gate.allowed)
            Gate.Open -> Unit
        }
        val pkg = fg.packageName!!
        val permit = ObservePermit(GUARD_KEY, pkg)
        val raw = try {
            read(permit)
        } finally {
            permit.revoke()
        }
        raw ?: return Observation.Halt(Finding(FindingCode.READ_FAILED, pkg))
        val screen = raw.screen
        if (screen.packageName != pkg) {
            return Observation.Halt(Finding(FindingCode.PACKAGE_CHANGED, screen.packageName))
        }
        when (val gate = screenGate(screen, policy)) {
            is Gate.Stop -> return Observation.Halt(gate.finding)
            is Gate.BlindOnly -> return Observation.Halt(gate.finding)
            Gate.Open -> Unit
        }
        return Observation.Visible(screen, raw.screenshotJpegBase64)
    }

    /**
     * 对一个将要执行的动作下结论。[screen] 必须是执行前刚取的屏幕。
     * Targeted 动作的 index 按 [UiTreeSerializer.visibleNodes] 对应到 [screen] 里的节点。
     *
     * @param confirmationKey 用户已经同意过的确认（来自上一次的 [Verdict.NeedsUserConfirm]）。
     *   只有同一个动作、同一个应用、同一个目标节点时才算数，且永远不能越过 Halt。
     */
    fun check(
        action: AgentAction,
        screen: ScreenState,
        policy: Policy,
        confirmationKey: String? = null,
    ): Verdict {
        val pkg = screen.packageName
        when (val gate = packageGate(pkg, screen.appLabel, screen.otherAppPackages, policy)) {
            is Gate.Stop -> return Verdict.Halt(gate.finding)
            is Gate.BlindOnly -> {
                if (action.kind !in gate.allowed) return Verdict.Halt(gate.finding)
                return if (action is AgentAction.OpenApp) {
                    openAppVerdict(action, screen, policy)
                } else {
                    allow(action, screen, target = null)
                }
            }
            Gate.Open -> Unit
        }
        contentGate(screen)?.let { return Verdict.Halt(it) }

        return when (action) {
            is AgentAction.OpenApp -> openAppVerdict(action, screen, policy)
            is AgentAction.Back, is AgentAction.Home -> allow(action, screen, target = null)
            is AgentAction.Targeted -> {
                val target = UiTreeSerializer.visibleNodes(screen.nodes).getOrNull(action.index)
                    ?: return Verdict.Halt(Finding(FindingCode.TARGET_MISSING, action.index.toString()))
                targetedVerdict(action, target, screen, confirmationKey)
            }
            // 这些动作不经设备层，走到这里说明调用方用错了
            is AgentAction.Wait, is AgentAction.Look, is AgentAction.AskUser, is AgentAction.Done ->
                Verdict.Halt(Finding(FindingCode.ACTION_NOT_ALLOWED_HERE, action.kind.wireName))
        }
    }

    /** 白名单页用：这个应用能不能被勾选。返回 null 表示可以。 */
    fun allowlistBlockReason(packageName: String, appLabel: String?, policy: Policy): Finding? {
        if (packageName == policy.ownPackage) return Finding(FindingCode.STARMATE_ITSELF, packageName)
        if (packageName in policy.systemUiPackages) return Finding(FindingCode.SYSTEM_UI, packageName)
        packageCategoryFinding(packageName, policy)?.let { return it }
        if (appLabel.isNullOrBlank()) return Finding(FindingCode.NO_APP_NAME, packageName)
        appNameFinding(appLabel, policy)?.let { return it }
        if (packageName in policy.launcherPackages) return Finding(FindingCode.LAUNCHER, packageName)
        return null
    }

    // ---- 内部 ----

    private fun targetedVerdict(
        action: AgentAction.Targeted,
        target: UiNode,
        screen: ScreenState,
        confirmationKey: String?,
    ): Verdict {
        when (action) {
            is AgentAction.InputText -> {
                if (target.isPassword) return Verdict.Halt(Finding(FindingCode.PASSWORD_FIELD))
                SensitiveActionDetector.secretFieldWord(target)?.let {
                    return Verdict.Halt(Finding(FindingCode.SECRET_FIELD, it))
                }
            }
            is AgentAction.Tap, is AgentAction.LongPress -> {
                val word = SensitiveActionDetector.sensitiveWord(target)
                if (word != null) {
                    val key = confirmationKeyFor(action, target, screen)
                    if (confirmationKey != key) {
                        return Verdict.NeedsUserConfirm(Finding(FindingCode.SENSITIVE_TAP, word), key)
                    }
                }
            }
            is AgentAction.Scroll -> Unit
        }
        return allow(action, screen, target)
    }

    private fun openAppVerdict(action: AgentAction.OpenApp, screen: ScreenState, policy: Policy): Verdict {
        val target = action.packageName.trim()
        if (target.isEmpty()) return Verdict.Halt(Finding(FindingCode.OPEN_APP_NOT_ALLOWLISTED, target))
        if (target == policy.ownPackage) return Verdict.Halt(Finding(FindingCode.STARMATE_ITSELF, target))
        if (target in policy.systemUiPackages) return Verdict.Halt(Finding(FindingCode.SYSTEM_UI, target))
        packageCategoryFinding(target, policy)?.let { return Verdict.Halt(it) }
        val label = policy.labelOf(target)
        if (label.isNullOrBlank()) return Verdict.Halt(Finding(FindingCode.NO_APP_NAME, target))
        appNameFinding(label, policy)?.let { return Verdict.Halt(it) }
        if (target !in policy.allowlist) return Verdict.Halt(Finding(FindingCode.OPEN_APP_NOT_ALLOWLISTED, target))
        return allow(action, screen, target = null)
    }

    private fun allow(action: AgentAction, screen: ScreenState, target: UiNode?): Verdict =
        Verdict.Allow(ApprovedAction(GUARD_KEY, action, screen.packageName.orEmpty(), screen.snapshotId, target))

    private fun screenGate(screen: ScreenState, policy: Policy): Gate {
        val gate = packageGate(screen.packageName, screen.appLabel, screen.otherAppPackages, policy)
        if (gate != Gate.Open) return gate
        contentGate(screen)?.let { return Gate.Stop(it) }
        return Gate.Open
    }

    /** 只看内容的检查：界面树为空（G10）、收银台和密码键盘（G3）。 */
    private fun contentGate(screen: ScreenState): Finding? {
        if (screen.nodes.isEmpty()) return Finding(FindingCode.EMPTY_TREE, screen.packageName)
        PaymentDetector.detect(screen)?.let { (code, subject) -> return Finding(code, subject) }
        return null
    }

    /** 只看包名和应用名的检查。顺序决定报出哪条规则：G2 先于 G1。 */
    private fun packageGate(
        packageName: String?,
        appLabel: String?,
        otherAppPackages: Set<String>,
        policy: Policy,
    ): Gate {
        if (packageName.isNullOrBlank()) return Gate.Stop(Finding(FindingCode.NO_FOREGROUND))

        // 分屏、小窗里同时开着黑名单应用：整个任务停下
        for (other in otherAppPackages) {
            if (other == policy.ownPackage || other in policy.systemUiPackages) continue
            val hit = packageCategoryFinding(other, policy) ?: policy.labelOf(other)?.let { appNameFinding(it, policy) }
            if (hit != null) return Gate.Stop(Finding(FindingCode.OTHER_WINDOW_BLOCKED, other, hit.category))
        }

        if (packageName in policy.systemUiPackages) {
            return Gate.BlindOnly(Finding(FindingCode.SYSTEM_UI, packageName), setOf(ActionKind.BACK, ActionKind.HOME))
        }
        if (packageName == policy.ownPackage) {
            // 任务从聊天里发起，开始时前台就是星伴：不读、不点，只能去打开白名单应用或回桌面
            return Gate.BlindOnly(
                Finding(FindingCode.STARMATE_ITSELF, packageName),
                setOf(ActionKind.OPEN_APP, ActionKind.HOME),
            )
        }
        packageCategoryFinding(packageName, policy)?.let { return Gate.Stop(it) }
        if (appLabel.isNullOrBlank()) return Gate.Stop(Finding(FindingCode.NO_APP_NAME, packageName))
        appNameFinding(appLabel, policy)?.let { return Gate.Stop(it) }
        if (packageName in policy.launcherPackages) {
            return Gate.BlindOnly(Finding(FindingCode.LAUNCHER, packageName), setOf(ActionKind.OPEN_APP))
        }
        if (packageName !in policy.allowlist) return Gate.Stop(Finding(FindingCode.NOT_ALLOWLISTED, packageName))
        return Gate.Open
    }

    /** 包名命中内置表（G2 / G6 / G7）或是默认短信应用（G7）。 */
    private fun packageCategoryFinding(packageName: String, policy: Policy): Finding? {
        val category = policy.blockList.categoryOfPackage(packageName)
        if (category != null) return Finding(codeFor(category, byName = false), packageName, category)
        if (packageName == policy.defaultSmsPackage) return Finding(FindingCode.DEFAULT_SMS_APP, packageName, BlockCategory.SMS)
        return null
    }

    private fun appNameFinding(appLabel: String, policy: Policy): Finding? {
        val (word, category) = policy.blockList.matchAppName(appLabel) ?: return null
        return Finding(codeFor(category, byName = true), word, category)
    }

    private fun codeFor(category: BlockCategory, byName: Boolean): FindingCode = when (category) {
        BlockCategory.SYSTEM -> FindingCode.SYSTEM_APP
        BlockCategory.AUTHENTICATOR, BlockCategory.PASSWORD_MANAGER, BlockCategory.SMS -> FindingCode.PROTECTED_APP
        else -> if (byName) FindingCode.BLOCKED_APP_NAME else FindingCode.BLOCKED_PACKAGE
    }

    private fun confirmationKeyFor(action: AgentAction.Targeted, target: UiNode, screen: ScreenState): String =
        "${action.kind.wireName}|${screen.packageName}|${NodeIdentity.of(target)}"
}
