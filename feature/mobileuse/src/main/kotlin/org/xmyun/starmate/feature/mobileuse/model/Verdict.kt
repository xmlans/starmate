package org.xmyun.starmate.feature.mobileuse.model

import org.xmyun.starmate.feature.mobileuse.guard.ApprovedAction
import org.xmyun.starmate.feature.mobileuse.guard.BlockCategory

/** 设计文档 5.8 的守卫规则编号。 */
enum class GuardRule { G1, G2, G3, G4, G5, G6, G7, G8, G9, G10 }

/** 守卫拦下或要求确认的具体原因。subject 是命中的包名、文字等，用于给用户和模型的说明。 */
enum class FindingCode(val rule: GuardRule) {
    NOT_ALLOWLISTED(GuardRule.G1),
    OPEN_APP_NOT_ALLOWLISTED(GuardRule.G1),
    LAUNCHER(GuardRule.G1),
    BLOCKED_PACKAGE(GuardRule.G2),
    BLOCKED_APP_NAME(GuardRule.G2),
    OTHER_WINDOW_BLOCKED(GuardRule.G2),
    CASHIER_TEXT(GuardRule.G3),
    PASSWORD_KEYBOARD(GuardRule.G3),
    PIN_PAD(GuardRule.G3),
    PASSWORD_FIELD(GuardRule.G4),
    SECRET_FIELD(GuardRule.G4),
    SENSITIVE_TAP(GuardRule.G5),
    SYSTEM_APP(GuardRule.G6),
    STARMATE_ITSELF(GuardRule.G6),
    SYSTEM_UI(GuardRule.G6),
    PROTECTED_APP(GuardRule.G7),
    DEFAULT_SMS_APP(GuardRule.G7),
    NO_FOREGROUND(GuardRule.G10),
    NO_APP_NAME(GuardRule.G10),
    EMPTY_TREE(GuardRule.G10),
    READ_FAILED(GuardRule.G10),
    PACKAGE_CHANGED(GuardRule.G10),
    TARGET_MISSING(GuardRule.G10),
    ACTION_NOT_ALLOWED_HERE(GuardRule.G10),
}

data class Finding(
    val code: FindingCode,
    val subject: String? = null,
    val category: BlockCategory? = null,
) {
    val rule: GuardRule get() = code.rule
}

/** Guard.check 的结论。 */
sealed interface Verdict {
    /** 放行。只有这里拿到的 [ApprovedAction] 能交给 DeviceController 执行。 */
    data class Allow(val approved: ApprovedAction) : Verdict

    /** 需要用户确认。用户同意后必须带着 [confirmationKey] 重新取屏幕再过一次守卫。 */
    data class NeedsUserConfirm(val finding: Finding, val confirmationKey: String) : Verdict

    /** 拦截，任务结束。 */
    data class Halt(val finding: Finding) : Verdict {
        val rule: GuardRule get() = finding.rule
    }
}

/** Guard.canObserve 的结论。 */
sealed interface Observation {
    /** 读到了界面树（和截图），内容检查也已通过，可以发给模型。 */
    data class Visible(val screen: ScreenState, val screenshotJpegBase64: String?) : Observation

    /**
     * 不读界面树，但任务不结束：系统界面、星伴自己、桌面在前台时，模型只能用 [allowed] 里的动作离开。
     */
    data class Blind(val foreground: Foreground, val finding: Finding, val allowed: Set<ActionKind>) : Observation

    /** 不能观察，任务结束。 */
    data class Halt(val finding: Finding) : Observation

    /** 是否读取了界面内容。 */
    val canSee: Boolean get() = this is Visible
}
