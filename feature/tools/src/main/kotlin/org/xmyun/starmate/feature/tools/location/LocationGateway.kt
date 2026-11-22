package org.xmyun.starmate.feature.tools.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 一次定位。precise 为假表示用户只给了粗略位置。 */
data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    val at: Instant,
    val precise: Boolean,
)

interface LocationGateway {
    /** 粗略或精确定位任一已授予。 */
    fun hasPermission(): Boolean

    /** 取当前位置。只在 App 前台时调用（不申请后台定位）。取不到返回 null。 */
    suspend fun currentFix(): GeoFix?

    /** 系统 Geocoder 能给出时返回城市名。 */
    suspend fun cityName(latitude: Double, longitude: Double): String?
}

/** 只用系统 LocationManager，不依赖 Google Play 服务。 */
class AndroidLocationGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) : LocationGateway {

    private val manager: LocationManager? get() = context.getSystemService(LocationManager::class.java)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun fineGranted() = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun hasPermission(): Boolean = fineGranted() || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    @SuppressLint("MissingPermission")
    override suspend fun currentFix(): GeoFix? {
        if (!hasPermission()) return null
        val lm = manager ?: return null
        val precise = fineGranted()
        val providers = buildList {
            // 只有粗略权限时 GPS 不可用；Android 12+ 的 fused 提供者两种权限都能用
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            if (precise) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null

        // 先看最近的已知位置，10 分钟内的直接用
        val lastKnown = providers.mapNotNull { p ->
            try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null }
        }.maxByOrNull { it.time }
        if (lastKnown != null && Duration.between(Instant.ofEpochMilli(lastKnown.time), clock.instant()) < FRESH_ENOUGH) {
            return lastKnown.toFix(precise)
        }
        for (provider in providers) {
            val location = withTimeoutOrNull(SINGLE_FIX_TIMEOUT_MS) { singleFix(lm, provider) }
            if (location != null) return location.toFix(precise, clock.instant())
        }
        return lastKnown?.toFix(precise)
    }

    @SuppressLint("MissingPermission")
    private suspend fun singleFix(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    // 回调只是恢复协程，用主线程的执行器就行，不必为每次定位新建线程（原先失败时还漏关）
                    lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                        if (cont.isActive) cont.resume(location)
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (cont.isActive) cont.resume(location)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                        override fun onProviderDisabled(provider: String) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    cont.invokeOnCancellation { lm.removeUpdates(listener) }
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(null)
            } catch (e: IllegalArgumentException) {
                if (cont.isActive) cont.resume(null)
            }
        }

    override suspend fun cityName(latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val address: Address? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
                }
            }
        } catch (e: Exception) {
            null
        }
        return address?.let { it.locality ?: it.subAdminArea ?: it.adminArea }?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun Location.toFix(precise: Boolean, at: Instant = Instant.ofEpochMilli(time)) =
        GeoFix(latitude, longitude, at, precise)

    companion object {
        private val FRESH_ENOUGH: Duration = Duration.ofMinutes(10)
        private const val SINGLE_FIX_TIMEOUT_MS = 6_000L
    }
}
