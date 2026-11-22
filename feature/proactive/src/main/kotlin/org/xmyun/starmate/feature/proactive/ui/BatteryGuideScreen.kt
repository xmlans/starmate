package org.xmyun.starmate.feature.proactive.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.xmyun.starmate.core.ui.component.SettingsChevron
import org.xmyun.starmate.core.ui.component.SettingsRow
import org.xmyun.starmate.core.ui.component.SettingsScaffold
import org.xmyun.starmate.core.ui.contract.LocalAppEdition
import org.xmyun.starmate.core.ui.component.SettingsSection
import org.xmyun.starmate.feature.proactive.R
import org.xmyun.starmate.feature.proactive.notify.NotificationPermission

/** 各厂商的自启动 / 后台设置页。组件名来自社区整理，系统版本不同可能打不开，打不开就退回应用详情页。 */
enum class PhoneVendor(
    @StringRes val label: Int,
    @StringRes val steps: Int,
    private val brands: Set<String>,
    val components: List<ComponentName>,
) {
    XIAOMI(
        R.string.proactive_vendor_xiaomi,
        R.string.proactive_vendor_xiaomi_steps,
        setOf("xiaomi", "redmi", "poco"),
        listOf(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
    ),
    HUAWEI(
        R.string.proactive_vendor_huawei,
        R.string.proactive_vendor_huawei_steps,
        setOf("huawei", "honor"),
        listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ),
    ),
    OPPO(
        R.string.proactive_vendor_oppo,
        R.string.proactive_vendor_oppo_steps,
        setOf("oppo", "realme", "oneplus"),
        listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ),
    ),
    VIVO(
        R.string.proactive_vendor_vivo,
        R.string.proactive_vendor_vivo_steps,
        setOf("vivo", "iqoo"),
        listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ),
    ),
    SAMSUNG(
        R.string.proactive_vendor_samsung,
        R.string.proactive_vendor_samsung_steps,
        setOf("samsung"),
        listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ),
    ),
    ;

    fun matches(manufacturer: String, brand: String): Boolean =
        manufacturer.lowercase() in brands || brand.lowercase() in brands
}

object BatteryGuide {
    fun isIgnoringOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    /**
     * 直接弹出系统的“允许忽略电池优化”对话框；不行就打开电池优化列表，再不行打开应用详情。
     * [allowDirectRequest] 为 false 时（Play 版没有这个权限）跳过对话框，直接打开电池优化列表让用户自己找到星伴。
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreOptimizations(context: Context, allowDirectRequest: Boolean) {
        if (allowDirectRequest) {
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            if (tryStart(context, direct)) return
        }
        if (tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        NotificationPermission.openAppDetails(context)
    }

    fun openVendorSettings(context: Context, vendor: PhoneVendor) {
        for (component in vendor.components) {
            if (tryStart(context, Intent().setComponent(component))) return
        }
        NotificationPermission.openAppDetails(context)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /** 当前手机的厂商排在最前面。 */
    fun orderedVendors(): List<PhoneVendor> {
        val mine = PhoneVendor.entries.filter { it.matches(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty()) }
        return mine + (PhoneVendor.entries - mine.toSet())
    }
}

@Composable
fun BatteryGuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val edition = LocalAppEdition.current
    var ignoring by remember { mutableStateOf(BatteryGuide.isIgnoringOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        ignoring = BatteryGuide.isIgnoringOptimizations(context)
        onPauseOrDispose { }
    }
    val vendors = remember { BatteryGuide.orderedVendors() }
    val mine = remember { vendors.filter { it.matches(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty()) }.toSet() }

    SettingsScaffold(title = stringResource(R.string.proactive_battery_title), onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = stringResource(R.string.proactive_battery_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }
            item {
                SettingsSection(title = stringResource(R.string.proactive_battery_opt_section)) {
                    SettingsRow(
                        icon = if (ignoring) Icons.Outlined.CheckCircle else Icons.Outlined.BatteryChargingFull,
                        title = stringResource(R.string.proactive_battery_opt_title),
                        summary = stringResource(
                            if (ignoring) R.string.proactive_battery_opt_granted else R.string.proactive_battery_opt_needed,
                        ),
                        onClick = if (ignoring) null else ({ BatteryGuide.requestIgnoreOptimizations(context, edition.batteryExemptionRequest) }),
                        trailing = if (ignoring) null else ({ SettingsChevron() }),
                    )
                }
            }
            items(vendors, key = { it.name }) { vendor ->
                val title = if (vendor in mine) {
                    stringResource(R.string.proactive_battery_your_phone, stringResource(vendor.label))
                } else {
                    stringResource(vendor.label)
                }
                SettingsSection(title = title) {
                    Column(Modifier.padding(16.dp)) {
                        Text(text = stringResource(vendor.steps), style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(
                            onClick = { BatteryGuide.openVendorSettings(context, vendor) },
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text(stringResource(R.string.proactive_battery_open))
                        }
                    }
                }
            }
            item {
                SettingsSection(title = stringResource(R.string.proactive_battery_other_section)) {
                    SettingsRow(
                        icon = Icons.Outlined.PhoneAndroid,
                        title = stringResource(R.string.proactive_battery_app_details),
                        summary = stringResource(R.string.proactive_battery_app_details_summary),
                        onClick = { NotificationPermission.openAppDetails(context) },
                        trailing = { SettingsChevron() },
                    )
                    SettingsRow(
                        icon = Icons.Outlined.Info,
                        title = stringResource(R.string.proactive_battery_keepalive_hint_title),
                        summary = stringResource(
                            if (edition.keepAlive) R.string.proactive_battery_keepalive_hint else R.string.proactive_battery_lock_hint,
                        ),
                    )
                }
            }
        }
    }
}
