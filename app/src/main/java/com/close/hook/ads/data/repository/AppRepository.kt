package com.close.hook.ads.data.repository

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.Build
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.util.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

class AppRepository(private val packageManager: PackageManager) {

    suspend fun getInstalledApps(isSystem: Boolean): List<AppInfo> = coroutineScope {
        val allPackages = packageManager.getInstalledPackages(0)

        allPackages.filter {
            isSystemApp(it.applicationInfo) == isSystem
        }.map { packageInfo ->
            async(Dispatchers.IO) {
                getAppInfo(packageInfo)
            }
        }.awaitAll().sortedBy { it.appName.lowercase() }
    }

    private fun getAppInfo(packageInfo: PackageInfo): AppInfo {
        val applicationInfo = packageInfo.applicationInfo
        val appName = packageManager.getApplicationLabel(applicationInfo).toString()
        val appIcon = packageManager.getApplicationIcon(applicationInfo)
        val size = File(applicationInfo.sourceDir).length()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            packageInfo.versionCode
        }
        val isAppEnable = getIsAppEnable(packageInfo.packageName)
        val isEnable = if (MainActivity.isModuleActivated()) AppUtils.isAppEnabled(packageInfo.packageName) else 0

        return AppInfo(
            appName,
            packageInfo.packageName,
            appIcon,
            packageInfo.versionName,
            versionCode,
            packageInfo.firstInstallTime,
            packageInfo.lastUpdateTime,
            size,
            applicationInfo.targetSdkVersion,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) applicationInfo.minSdkVersion else 0,
            isAppEnable,
            isEnable
        )
    }

    private fun isSystemApp(applicationInfo: ApplicationInfo): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0

    private fun getIsAppEnable(packageName: String): Int =
        if (packageManager.getApplicationEnabledSetting(packageName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) 1 else 0
}
