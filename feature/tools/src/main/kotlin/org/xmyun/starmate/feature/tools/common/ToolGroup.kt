package org.xmyun.starmate.feature.tools.common

/** 六组本地工具。hasAmbient 为假的组没有“让她随时知道”这一档。 */
enum class ToolGroup(val key: String, val hasAmbient: Boolean) {
    CALENDAR("calendar", hasAmbient = true),
    ALARM("alarm", hasAmbient = false),
    CONTACTS("contacts", hasAmbient = false),
    LOCATION("location", hasAmbient = true),
    HEALTH("health", hasAmbient = true),
    MUSIC("music", hasAmbient = true);

    companion object {
        fun fromKey(key: String?): ToolGroup? = entries.firstOrNull { it.key == key }
    }
}

/** 设置页上每组的状态。 */
enum class GroupStatus { OFF, MISSING_PERMISSION, ON_DEMAND, AMBIENT }

/** 写入类系统操作的结果。 */
enum class WriteOutcome { OK, NO_PERMISSION, NO_APP, NO_CALENDAR, FAILED }
