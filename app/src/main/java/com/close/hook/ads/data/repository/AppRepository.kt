package com.close.hook.ads.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import com.close.hook.ads.R
import com.close.hook.ads.closeApp
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.hook.preference.PreferencesHelper;
import com.close.hook.ads.util.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

class AppRepository(
    private val packageManager: PackageManager,
    private val context: Context
) {

    private val localizedContext by lazy {
        val config = Configuration(context.resources.configuration)
        config.setLocale(closeApp.getLocale(PrefManager.language))
        context.createConfigurationContext(config)
    }

    private val semaphore = Semaphore(20)

    private fun getLocalizedString(resId: Int): String {
        return localizedContext.getString(resId)
    }

    suspend fun getInstalledApps(isSystem: Boolean? = null): List<AppInfo> = withContext(Dispatchers.IO) {
        val installedPackages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
            .filter { packageInfo ->
                isSystem == null || isSystemApp(packageInfo.applicationInfo) == isSystem
            }

        val resultList = installedPackages.chunked(50).flatMap { chunk ->
            chunk.map { packageInfo ->
                async {
                    semaphore.withPermit {
                        getAppInfo(packageInfo)
                    }
                }
            }.awaitAll()
        }

        resultList.sortedBy { it.appName.lowercase() }
    }

    suspend fun getFilteredAndSortedApps(
        apps: List<AppInfo>,
        filter: Pair<String, List<String>>,
        keyword: String,
        isReverse: Boolean
    ): List<AppInfo> = withContext(Dispatchers.Default) {
        val comparator = getComparator(filter.first, isReverse)

        val sortedApps = apps.asSequence()
            .filter { app -> matchesKeyword(app, keyword) && matchesFilter(app, filter.second) }
            .sortedWith(comparator)
            .toList()

        sortedApps
    }

    private fun matchesKeyword(app: AppInfo, keyword: String): Boolean {
        return keyword.isBlank() || app.appName.contains(keyword, ignoreCase = true) ||
                app.packageName.contains(keyword, ignoreCase = true)
    }

    private fun matchesFilter(app: AppInfo, filterCriteria: List<String>): Boolean {
        return filterCriteria.all { criterion ->
            when (criterion) {
                getLocalizedString(R.string.filter_configured) -> app.isEnable == 1
                getLocalizedString(R.string.filter_recent_update) ->
                    System.currentTimeMillis() - app.lastUpdateTime < 3 * 24 * 3600 * 1000L
                getLocalizedString(R.string.filter_disabled) -> app.isAppEnable == 0
                else -> true
            }
        }
    }

    private fun getComparator(sortBy: String, isReverse: Boolean): Comparator<AppInfo> {
        val comparator = when (sortBy) {
            getLocalizedString(R.string.sort_by_app_size) -> compareBy<AppInfo> { it.size }
            getLocalizedString(R.string.sort_by_last_update) -> compareBy { it.lastUpdateTime }
            getLocalizedString(R.string.sort_by_install_date) -> compareBy { it.firstInstallTime }
            getLocalizedString(R.string.sort_by_target_version) -> compareBy { it.targetSdk }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName }
        }

        return if (isReverse) comparator.reversed() else comparator
    }

    private suspend fun getAppInfo(packageInfo: PackageInfo): AppInfo = withContext(Dispatchers.IO) {
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
            if (MainActivity.isModuleActivated()) PreferencesHelper.isAppHooked(packageInfo.packageName) else 0

        AppInfo(
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
