package org.xmyun.starmate.feature.mobileuse.tool

import android.os.Build
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import org.xmyun.starmate.feature.mobileuse.prefs.MobileUsePrefs
import org.xmyun.starmate.feature.mobileuse.service.AccessibilityBridge

/** phone_task 能否提供的全部条件。 */
data class AvailabilityState(
    val enabled: Boolean,
    val riskAccepted: Boolean,
    val sdkInt: Int,
    val serviceConnected: Boolean,
    val allowlistSize: Int,
) {
    val available: Boolean
        get() = enabled && riskAccepted && sdkInt >= MIN_SDK && serviceConnected && allowlistSize > 0

    companion object {
        const val MIN_SDK = 30
    }
}

fun interface AvailabilitySource {
    suspend fun current(): AvailabilityState
}

class AndroidAvailabilitySource @Inject constructor(
    private val prefs: MobileUsePrefs,
    private val bridge: AccessibilityBridge,
) : AvailabilitySource {
    override suspend fun current() = AvailabilityState(
        enabled = prefs.enabled.first(),
        riskAccepted = prefs.riskAccepted.first(),
        sdkInt = Build.VERSION.SDK_INT,
        serviceConnected = bridge.service.value != null,
        allowlistSize = prefs.allowlist.first().size,
    )
}
