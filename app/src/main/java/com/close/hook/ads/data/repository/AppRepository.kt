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
import com.close.hook.ads.hook.preference.PreferencesHelper
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.util.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class AppRepository(
    private val packageManager: PackageManager,
    private val context: Context
) {

    private val enableKeys = arrayOf(
        "switch_one_", "switch_two_", "switch_three_", "switch_four_",
        "switch_five_", "switch_six_", "switch_seven_", "switch_eight_"
    )

    private val prefsHelper: PreferencesHelper by lazy {
        PreferencesHelper(closeApp)
    }

    private val localizedContext: Context by lazy {
        val config = Configuration(context.resources.configuration).apply {
            setLocale(closeApp.getLocale(PrefManager.language))
        }
        context.applicationContext.createConfigurationContext(config)
    }

    private fun getLocalizedString(resId: Int): String =
        localizedContext.getString(resId)

    suspend fun getInstalledApps(isSystem: Boolean? = null): List<AppInfo> = withContext(Dispatchers.IO) {
        val packages = runCatching {
            packageManager.getInstalledPackages(0)
        }.getOrElse { emptyList() }

        packages.asSequence()
            .filter { isSystem == null || isSystemApp(it.applicationInfo) == isSystem }
            .mapNotNull { packageInfo ->
                runCatching { getAppInfo(packageInfo) }.getOrNull()
            }
            .sortedWith(Comparator { a, b ->
                String.CASE_INSENSITIVE_ORDER.compare(a.appName, b.appName)
            })
            .toList()
    }

    suspend fun getFilteredAndSortedApps(
        apps: List<AppInfo>,
        filter: Pair<String, List<String>>,
        keyword: String,
        isReverse: Boolean
    ): List<AppInfo> = withContext(Dispatchers.Default) {
        val comparator = getComparator(filter.first, isReverse)
        val now = System.currentTimeMillis()

        val filterConfigured = getLocalizedString(R.string.filter_configured)
        val filterRecentUpdate = getLocalizedString(R.string.filter_recent_update)
        val filterDisabled = getLocalizedString(R.string.filter_disabled)

        apps.asSequence()
            .filter { app ->
                matchesKeyword(app, keyword) &&
                matchesFilter(app, filter.second, now, filterConfigured, filterRecentUpdate, filterDisabled)
            }
            .sortedWith(comparator)
            .toList()
    }

    private fun matchesKeyword(app: AppInfo, keyword: String): Boolean =
        keyword.isBlank() ||
        app.appName.contains(keyword, ignoreCase = true) ||
        app.packageName.contains(keyword, ignoreCase = true)

    private fun matchesFilter(
        app: AppInfo,
        criteria: List<String>,
        now: Long,
        filterConfigured: String,
        filterRecentUpdate: String,
        filterDisabled: String
    ): Boolean = criteria.all { criterion ->
        when (criterion) {
            filterConfigured -> app.isEnable == 1
            filterRecentUpdate -> now - app.lastUpdateTime < 3 * 24 * 3600 * 1000L
            filterDisabled -> app.isAppEnable == 0
            else -> true
        }
    }

    private fun getComparator(sortBy: String, isReverse: Boolean): Comparator<AppInfo> {
        val baseComparator = when (sortBy) {
            getLocalizedString(R.string.sort_by_app_size) -> compareBy<AppInfo> { it.size }
            getLocalizedString(R.string.sort_by_last_update) -> compareBy { it.lastUpdateTime }
            getLocalizedString(R.string.sort_by_install_date) -> compareBy { it.firstInstallTime }
            getLocalizedString(R.string.sort_by_target_version) -> compareBy { it.targetSdk }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName }
        }
        return if (isReverse) baseComparator.reversed() else baseComparator
    }

    private fun getAppInfo(packageInfo: PackageInfo): AppInfo {
        val appInfo = packageInfo.applicationInfo
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }

        return AppInfo(
            appName = appInfo.loadLabel(packageManager).toString(),
            packageName = packageInfo.packageName,
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = versionCode,
            firstInstallTime = packageInfo.firstInstallTime,
            lastUpdateTime = packageInfo.lastUpdateTime,
            size = File(appInfo.sourceDir).length(),
            targetSdk = appInfo.targetSdkVersion,
            minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 0,
            isAppEnable = getIsAppEnable(packageInfo.packageName),
            isEnable = if (MainActivity.isModuleActivated()) isAppHooked(packageInfo.packageName) else 0
        )
    }

    fun isAppHooked(packageName: String): Int {
        return if (enableKeys.any { prefsHelper.getBoolean(it + packageName, false) }) 1 else 0
    }

    private fun isSystemApp(appInfo: ApplicationInfo): Boolean =
        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    private fun getIsAppEnable(packageName: String): Int =
        if (packageManager.getApplicationEnabledSetting(packageName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) 1 else 0
}
