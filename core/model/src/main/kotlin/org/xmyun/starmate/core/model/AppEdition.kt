package org.xmyun.starmate.core.model

/**
 * 这个安装包是哪个版本，以及各版本之间不同的能力。由 :app 按产品变体提供（见 app/build.gradle.kts）。
 *
 * - stable：GitHub 发布的正式版，不含手机操作。
 * - beta：完整功能，含手机操作。
 * - play：Google Play 特供版。在正式版的基础上去掉 Play 不允许的保活服务和电池优化豁免请求，加上举报入口。
 */
data class AppEdition(
    val id: String,
    val versionName: String,
    /** 能不能开保活前台服务。 */
    val keepAlive: Boolean,
    /** 能不能直接弹出系统的“忽略电池优化”对话框（需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限）。 */
    val batteryExemptionRequest: Boolean,
    /** 长按她的回复能不能“举报”。 */
    val contentReports: Boolean,
    /** 开发者联系邮箱，举报时用。 */
    val contactEmail: String,
) {
    companion object {
        /** 测试和界面预览用，能力和正式版相同。 */
        val Default = AppEdition(
            id = "stable",
            versionName = "dev",
            keepAlive = true,
            batteryExemptionRequest = true,
            contentReports = false,
            contactEmail = "support@xmyun.org",
        )
    }
}
