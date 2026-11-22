package org.xmyun.starmate.feature.proactive.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

interface NetworkStatus {
    fun isOnline(): Boolean
}

@Singleton
class ConnectivityNetworkStatus @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkStatus {
    override fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return try {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: SecurityException) {
            // 清单里声明了 ACCESS_NETWORK_STATE，正常不会走到这里；拿不到就不拦
            true
        }
    }
}

/** 随机数来源，测试里换成固定值。 */
interface RandomSource {
    /** [from, until) 内的随机数。 */
    fun nextLong(from: Long, until: Long): Long
}

class DefaultRandomSource @Inject constructor() : RandomSource {
    override fun nextLong(from: Long, until: Long): Long = Random.nextLong(from, until)
}
