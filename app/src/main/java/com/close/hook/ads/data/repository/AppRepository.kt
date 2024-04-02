package com.close.hook.ads.data.repository

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.Build
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.util.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

class AppRepository(private val packageManager: PackageManager) {

    suspend fun getInstalledApps(isSystem: Boolean? = null): List<AppInfo> = coroutineScope {
        var allPackages = packageManager.getInstalledPackages(0)
        isSystem?.let {
            allPackages = allPackages.filter {
                isSystemApp(it.applicationInfo) == isSystem
            }
        }

        allPackages.map { packageInfo ->
            async(Dispatchers.IO) {
                getAppInfo(packageInfo)
            }
        }.awaitAll().sortedBy { it.appName.lowercase() }
    }

    suspend fun getFilteredAndSortedApps(
        apps: List<AppInfo>,
        filter: Pair<String, List<String>>,
        keyword: String,
        isReverse: Boolean
    ): List<AppInfo> = withContext(Dispatchers.Default) {
        var filteredApps = apps

        // 关键字搜索
        if (keyword.isNotBlank()) {
            filteredApps = filteredApps.filter {
                it.appName.lowercase().contains(keyword.lowercase()) ||
                it.packageName.lowercase().contains(keyword.lowercase())
            }
        }

        // 过滤
        filter.second.forEach { filterTitle ->
            filteredApps = when (filterTitle) {
                "已配置" -> filteredApps.filter { it.isEnable == 1 }
                "最近更新" -> {
                    val time = 3 * 24 * 3600 * 1000L
                    filteredApps.filter { System.currentTimeMillis() - it.lastUpdateTime < time }
                }
                "已禁用" -> filteredApps.filter { it.isAppEnable == 0 }
                else -> filteredApps
            }
        }

        // 排序
        val comparator = when (filter.first) {
            "应用大小" -> compareBy<AppInfo> { it.size }
            "最近更新时间" -> compareBy { it.lastUpdateTime }
            "安装日期" -> compareBy { it.firstInstallTime }
            "Target 版本" -> compareBy { it.targetSdk }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName }
        }.let { if (isReverse) it.reversed() else it }

        filteredApps.sortedWith(comparator)
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
        val isEnable =
            if (MainActivity.isModuleActivated()) AppUtils.isAppEnabled(packageInfo.packageName) else 0

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
