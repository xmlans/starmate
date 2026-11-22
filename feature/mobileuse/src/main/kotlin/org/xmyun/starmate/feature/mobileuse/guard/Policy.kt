package org.xmyun.starmate.feature.mobileuse.guard

/**
 * 守卫判断时用到的全部外部信息，每一步由 PolicyProvider 重新生成。
 *
 * @param allowlist 用户勾选的应用包名（G1）。
 * @param ownPackage 星伴自己的包名（G6）。
 * @param defaultSmsPackage 运行时查询的默认短信应用（G7），查不到时为 null。
 * @param launcherPackages 桌面应用。桌面在前台时不读界面，只允许打开白名单应用。
 * @param systemUiPackages 系统界面（通知栏、锁屏、系统弹窗）。在前台时只允许 back、home。
 * @param labelOf 查询应用名，用于 open_app 目标的关键词检查。查不到返回 null。
 */
class Policy(
    val allowlist: Set<String>,
    val blockList: BlockList,
    val ownPackage: String,
    val defaultSmsPackage: String? = null,
    val launcherPackages: Set<String> = emptySet(),
    val systemUiPackages: Set<String> = DEFAULT_SYSTEM_UI,
    val labelOf: (String) -> String? = { null },
) {
    companion object {
        val DEFAULT_SYSTEM_UI = setOf("com.android.systemui")
    }
}
