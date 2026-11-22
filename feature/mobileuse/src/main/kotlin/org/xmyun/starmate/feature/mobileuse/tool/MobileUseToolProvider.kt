package org.xmyun.starmate.feature.mobileuse.tool

import javax.inject.Inject
import org.xmyun.starmate.core.model.Tool
import org.xmyun.starmate.core.model.ToolProvider
import org.xmyun.starmate.core.model.TurnPurpose

/**
 * G8：phone_task 只在用途为 CHAT 时提供，且要求功能已开启、风险说明已读、Android 11+、
 * 无障碍服务已连接、白名单非空。其他用途（主动消息、日记、记忆整理等）下这个工具不存在。
 */
class MobileUseToolProvider @Inject constructor(
    private val availability: AvailabilitySource,
    private val tool: PhoneTaskTool,
) : ToolProvider {
    override suspend fun tools(purpose: TurnPurpose): List<Tool> {
        if (purpose != TurnPurpose.CHAT) return emptyList()
        return if (availability.current().available) listOf(tool) else emptyList()
    }
}
