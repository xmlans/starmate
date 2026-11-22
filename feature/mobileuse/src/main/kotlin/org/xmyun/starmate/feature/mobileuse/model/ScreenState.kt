package org.xmyun.starmate.feature.mobileuse.model

/**
 * 前台窗口的元信息，不含任何界面内容。守卫据此决定能不能读界面树。
 *
 * @param otherAppPackages 同时可见的其他应用窗口（分屏、小窗）的包名，不含输入法和系统栏。
 */
data class Foreground(
    val packageName: String?,
    val appLabel: String?,
    val otherAppPackages: Set<String> = emptySet(),
)

/**
 * 一次读取到的屏幕状态。
 *
 * @param snapshotId 每次读取递增。执行动作时设备层用它确认动作针对的还是这一份快照。
 * @param passwordKeyboardShown 设备层判断的“密码键盘正在显示”（输入法弹出且焦点在密码框上，或已知的安全键盘）。
 */
data class ScreenState(
    val packageName: String?,
    val appLabel: String?,
    val nodes: List<UiNode>,
    val passwordKeyboardShown: Boolean = false,
    val otherAppPackages: Set<String> = emptySet(),
    val snapshotId: Long = 0,
) {
    val foreground: Foreground get() = Foreground(packageName, appLabel, otherAppPackages)

    companion object {
        /** 只有前台信息、没有读界面树时用（例如系统界面在前台）。 */
        fun headerOnly(foreground: Foreground): ScreenState = ScreenState(
            packageName = foreground.packageName,
            appLabel = foreground.appLabel,
            nodes = emptyList(),
            otherAppPackages = foreground.otherAppPackages,
        )
    }
}

/** 设备层读到的原始结果：界面树和（可选）同一时刻的截图。 */
data class RawObservation(
    val screen: ScreenState,
    /** JPEG 的 base64，没有请求截图或截图失败时为 null。 */
    val screenshotJpegBase64: String? = null,
)
