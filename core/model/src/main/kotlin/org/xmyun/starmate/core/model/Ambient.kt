package org.xmyun.starmate.core.model

interface AmbientSource {
    val id: String

    /** 一行文字，例如“今天步数 3200”。关闭、未授权或无数据时返回 null。必须很快，超过 2 秒会被引擎丢弃。 */
    suspend fun snapshot(): String?
}
