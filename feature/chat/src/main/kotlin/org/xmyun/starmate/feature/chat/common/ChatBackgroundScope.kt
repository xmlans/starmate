package org.xmyun.starmate.feature.chat.common

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 本模块自己的进程级协程作用域：访谈抽取、落盘这类不能随页面取消的工作在这里跑。
 * 包一层而不是直接绑定 CoroutineScope，避免和其他模块撞绑定。
 */
@Singleton
class ChatBackgroundScope(val scope: CoroutineScope) {
    @Inject
    constructor() : this(CoroutineScope(SupervisorJob() + Dispatchers.Default))
}
