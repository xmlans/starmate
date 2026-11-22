package org.xmyun.starmate.feature.mobileuse.agent

/** 手机操作任务结束后把星伴带回前台，让用户直接看到她的回复。 */
interface AppReturner {
    suspend fun bringBack()

    /** 测试和不需要回到前台的场合用。 */
    object None : AppReturner {
        override suspend fun bringBack() = Unit
    }
}
