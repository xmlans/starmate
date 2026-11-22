package org.xmyun.starmate.feature.tools.health

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

enum class HealthAvailability { AVAILABLE, NEEDS_UPDATE, UNAVAILABLE }

enum class HealthData { SLEEP, STEPS }

data class SleepSession(val start: Instant, val end: Instant)

interface HealthGateway {
    fun availability(): HealthAvailability

    /** 已授予的 Health Connect 读权限。Health Connect 不可用时为空。 */
    suspend fun granted(): Set<HealthData>

    suspend fun sleepSessions(from: Instant, to: Instant): List<SleepSession>

    /** 区间内的总步数；没有任何记录时为 null。 */
    suspend fun steps(from: Instant, to: Instant): Long?

    /** 手机有计步传感器，且（Android 10+）已授予活动识别权限。 */
    fun stepCounterUsable(): Boolean
}

class HealthConnectGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : HealthGateway {

    private val client: HealthConnectClient? by lazy {
        if (availability() == HealthAvailability.AVAILABLE) {
            runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
        } else {
            null
        }
    }

    override fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.NEEDS_UPDATE
        else -> HealthAvailability.UNAVAILABLE
    }

    override suspend fun granted(): Set<HealthData> {
        val c = currentClient() ?: return emptySet()
        val permissions = try {
            c.permissionController.getGrantedPermissions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptySet()
        }
        return buildSet {
            if (SLEEP_PERMISSION in permissions) add(HealthData.SLEEP)
            if (STEPS_PERMISSION in permissions) add(HealthData.STEPS)
        }
    }

    override suspend fun sleepSessions(from: Instant, to: Instant): List<SleepSession> {
        val c = currentClient() ?: return emptyList()
        return try {
            c.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, to)),
            ).records.map { SleepSession(it.startTime, it.endTime) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun steps(from: Instant, to: Instant): Long? {
        val c = currentClient() ?: return null
        return try {
            c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(from, to)))[StepsRecord.COUNT_TOTAL]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override fun stepCounterUsable(): Boolean {
        val sensors = context.getSystemService(SensorManager::class.java) ?: return false
        if (sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) == null) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    }

    // 用户可能中途安装或更新了 Health Connect，状态变了就重新取
    private fun currentClient(): HealthConnectClient? =
        if (availability() == HealthAvailability.AVAILABLE) client ?: runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() else null

    companion object {
        val SLEEP_PERMISSION: String = HealthPermission.getReadPermission(SleepSessionRecord::class)
        val STEPS_PERMISSION: String = HealthPermission.getReadPermission(StepsRecord::class)
        val PERMISSIONS: Set<String> = setOf(SLEEP_PERMISSION, STEPS_PERMISSION)
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

        /** Android 13 及以下安装或更新 Health Connect 的入口（官方文档的写法）。 */
        fun installIntent(context: Context): Intent =
            Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.android.vending")
                data = Uri.parse("market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding")
                putExtra("overlay", true)
                putExtra("callerId", context.packageName)
            }
    }
}

/** 读一次计步传感器的累计值。 */
interface StepSensorReader {
    suspend fun read(): Long?
}

class AndroidStepSensorReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : StepSensorReader {
    override suspend fun read(): Long? {
        val manager = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) return null
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        manager.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values.firstOrNull()?.toLong())
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                cont.invokeOnCancellation { manager.unregisterListener(listener) }
                if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }
}
