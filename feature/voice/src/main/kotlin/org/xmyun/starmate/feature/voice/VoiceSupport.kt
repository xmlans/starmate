package org.xmyun.starmate.feature.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 当前界面语言是否中文。和引擎选内置准则的规则一致：系统首选语言是 zh。 */
interface VoiceLocale {
    fun chinese(): Boolean
}

class AndroidVoiceLocale @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceLocale {
    override fun chinese(): Boolean = context.resources.configuration.locales[0].language == "zh"
}

/** 语音模块自己的后台作用域（合成、更新附件）。包一层，避免在 Hilt 图里绑定裸的 CoroutineScope。 */
@Singleton
class VoiceWorkScope(val scope: CoroutineScope) {
    @Inject
    constructor() : this(CoroutineScope(SupervisorJob() + Dispatchers.IO))
}

/** 语音模块自己的 HTTP 客户端。不装日志插件；不自动抛 HTTP 错误，由调用处按状态码处理。 */
@Singleton
class VoiceHttp(val client: HttpClient) {
    @Inject
    constructor() : this(
        HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
                requestTimeoutMillis = 120_000
            }
        },
    )
}
