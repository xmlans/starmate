package org.xmyun.starmate.feature.mobileuse.model

enum class ActionKind(val wireName: String) {
    TAP("tap"),
    LONG_PRESS("long_press"),
    INPUT_TEXT("input_text"),
    SCROLL("scroll"),
    BACK("back"),
    HOME("home"),
    OPEN_APP("open_app"),
    WAIT("wait"),
    LOOK("look"),
    ASK_USER("ask_user"),
    DONE("done"),
    ;

    /** 需要经过守卫并由设备层执行的动作。 */
    val touchesDevice: Boolean
        get() = this == TAP || this == LONG_PRESS || this == INPUT_TEXT || this == SCROLL ||
            this == BACK || this == HOME || this == OPEN_APP

    companion object {
        fun fromWire(name: String): ActionKind? = entries.firstOrNull { it.wireName == name }
    }
}

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/** 模型每一步输出的一个动作。index 是发给模型的界面列表里的序号。 */
sealed interface AgentAction {
    val kind: ActionKind
    val why: String

    /** 作用在某个界面元素上的动作。 */
    sealed interface Targeted : AgentAction {
        val index: Int
    }

    data class Tap(override val index: Int, override val why: String = "") : Targeted {
        override val kind get() = ActionKind.TAP
    }

    data class LongPress(override val index: Int, override val why: String = "") : Targeted {
        override val kind get() = ActionKind.LONG_PRESS
    }

    data class InputText(override val index: Int, val text: String, override val why: String = "") : Targeted {
        override val kind get() = ActionKind.INPUT_TEXT
    }

    data class Scroll(override val index: Int, val direction: ScrollDirection, override val why: String = "") : Targeted {
        override val kind get() = ActionKind.SCROLL
    }

    data class Back(override val why: String = "") : AgentAction {
        override val kind get() = ActionKind.BACK
    }

    data class Home(override val why: String = "") : AgentAction {
        override val kind get() = ActionKind.HOME
    }

    data class OpenApp(val packageName: String, override val why: String = "") : AgentAction {
        override val kind get() = ActionKind.OPEN_APP
    }

    data class Wait(val seconds: Int, override val why: String = "") : AgentAction {
        override val kind get() = ActionKind.WAIT
    }

    data class Look(override val why: String = "") : AgentAction {
        override val kind get() = ActionKind.LOOK
    }

    data class AskUser(val question: String, override val why: String = "") : AgentAction {
        override val kind get() = ActionKind.ASK_USER
    }

    data class Done(val summary: String, override val why: String = "") : AgentAction {
        override val kind get() = ActionKind.DONE
    }
}
