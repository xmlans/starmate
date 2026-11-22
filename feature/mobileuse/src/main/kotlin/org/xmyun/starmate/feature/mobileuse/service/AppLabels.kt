package org.xmyun.starmate.feature.mobileuse.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledApp(val packageName: String, val label: String)

/** 查应用名、列出可启动的应用、找桌面应用。取不到时返回 null 或空，由守卫按拦截处理。 */
@Singleton
class AppLabels @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pm: PackageManager get() = context.packageManager

    fun labelOf(packageName: String): String? = try {
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    fun launchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .mapNotNull { pkg -> labelOf(pkg)?.let { InstalledApp(pkg, it) } }
            .sortedBy { it.label.lowercase() }
    }

    fun launcherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()
    }
}
