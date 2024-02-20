package com.close.hook.ads.data.repository

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.util.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AppRepository(private val packageManager: PackageManager) {

    companion object {
        private const val TAG = "AppRepository"
    }

    suspend fun getInstalledUserApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        fetchInstalledApps(false)
    }

    suspend fun getInstalledSystemApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        fetchInstalledApps(true)
    }

    private fun fetchInstalledApps(isSystem: Boolean): List<AppInfo> {
        val allPackages = packageManager.getInstalledPackages(0)
        val appInfos = mutableListOf<AppInfo>()

        allPackages.forEach { packageInfo ->
            if (isSystemApp(packageInfo.applicationInfo) == isSystem) {
                val appName = packageManager.getApplicationLabel(packageInfo.applicationInfo).toString()
                val appIcon = packageManager.getApplicationIcon(packageInfo.applicationInfo)
                val size = File(packageInfo.applicationInfo.sourceDir).length()
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode.toInt()
                } else {
                    packageInfo.versionCode
                }
                val isAppEnable = getIsAppEnable(packageInfo.packageName)
                val isEnable = if (MainActivity.isModuleActivated()) AppUtils.isAppEnabled(packageInfo.packageName) else 0

                appInfos.add(AppInfo(
                    appName,
                    packageInfo.packageName,
                    appIcon,
                    packageInfo.versionName,
                    versionCode,
                    packageInfo.firstInstallTime,
                    packageInfo.lastUpdateTime,
                    size,
                    packageInfo.applicationInfo.targetSdkVersion,
                    isAppEnable,
                    isEnable
                ))
            }
        }
        return appInfos.sortedBy { it.appName.lowercase() }
    }

    private fun isSystemApp(applicationInfo: ApplicationInfo): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0

    private fun getIsAppEnable(packageName: String): Int =
        if (packageManager.getApplicationEnabledSetting(packageName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) 1 else 0
}
