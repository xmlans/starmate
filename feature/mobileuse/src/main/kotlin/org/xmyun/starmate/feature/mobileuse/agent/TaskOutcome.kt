package org.xmyun.starmate.feature.mobileuse.agent

import org.xmyun.starmate.core.model.LlmErrorKind
import org.xmyun.starmate.feature.mobileuse.model.Finding

enum class StopReason { USER, SCREEN_OFF, SERVICE_DISCONNECTED }

enum class LimitKind { STEPS, TIME, TOKENS }

enum class FailReason { NO_MODEL, MODEL_ERROR, PARSE_FAILURES }

/** 一次手机操作任务的结果。 */
sealed interface TaskOutcome {
    /** 给日志用的短代码。 */
    val code: String

    /**
     * 结束后要不要回到星伴。任务自己结束的（完成、被守卫拦下、到上限、出错）都回去，
     * 用户能马上看到她的说明；被守卫在收银台这类页面拦下时，回去也正好离开那个页面。
     * 用户按了停止、熄屏或服务断开时留在原处：按停止多半是想自己接手当前的应用。
     */
    val returnsToStarmate: Boolean get() = this !is Stopped

    data class Done(val summary: String) : TaskOutcome {
        override val code get() = "DONE"
    }

    /** 守卫拦截，任务结束。 */
    data class Halted(val finding: Finding) : TaskOutcome {
        override val code get() = "HALT_${finding.rule}"
    }

    data class LimitReached(val kind: LimitKind) : TaskOutcome {
        override val code get() = "LIMIT_$kind"
    }

    data class Stopped(val reason: StopReason) : TaskOutcome {
        override val code get() = "STOP_$reason"
    }

    data class Failed(val reason: FailReason, val errorKind: LlmErrorKind? = null) : TaskOutcome {
        override val code get() = "FAIL_$reason"
    }
}
