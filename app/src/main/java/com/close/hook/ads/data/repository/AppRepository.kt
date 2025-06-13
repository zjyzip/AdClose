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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    private val localizedContext by lazy {
        val config = Configuration(context.resources.configuration).apply {
            setLocale(closeApp.getLocale(PrefManager.language))
        }
        context.createConfigurationContext(config)
    }

    private val semaphore = Semaphore(permits = 5)

    private fun getLocalizedString(resId: Int): String =
        localizedContext.getString(resId)

    suspend fun getInstalledApps(isSystem: Boolean? = null): List<AppInfo> = withContext(Dispatchers.IO) {
        val packages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
            .filter { isSystem == null || isSystemApp(it.applicationInfo) == isSystem }

        val resultList = packages.chunked(50).flatMap { chunk ->
            chunk.map { pkgInfo ->
                async {
                    semaphore.withPermit {
                        try {
                            getAppInfo(pkgInfo)
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        resultList.sortedBy { it.appName.lowercase(Locale.ROOT) }
    }

    suspend fun getFilteredAndSortedApps(
        apps: List<AppInfo>,
        filter: Pair<String, List<String>>,
        keyword: String,
        isReverse: Boolean
    ): List<AppInfo> = withContext(Dispatchers.Default) {
        val comparator = getComparator(filter.first, isReverse)
        apps.asSequence()
            .filter { app -> matchesKeyword(app, keyword) && matchesFilter(app, filter.second) }
            .sortedWith(comparator)
            .toList()
    }

    private fun matchesKeyword(app: AppInfo, keyword: String): Boolean =
        keyword.isBlank() ||
        app.appName.contains(keyword, ignoreCase = true) ||
        app.packageName.contains(keyword, ignoreCase = true)

    private fun matchesFilter(app: AppInfo, criteria: List<String>): Boolean =
        criteria.all { criterion ->
            when (criterion) {
                getLocalizedString(R.string.filter_configured) -> app.isEnable == 1
                getLocalizedString(R.string.filter_recent_update) ->
                    System.currentTimeMillis() - app.lastUpdateTime < 3 * 24 * 3600 * 1000L
                getLocalizedString(R.string.filter_disabled) -> app.isAppEnable == 0
                else -> true
            }
        }

    private fun getComparator(sortBy: String, isReverse: Boolean): Comparator<AppInfo> {
        val base = when (sortBy) {
            getLocalizedString(R.string.sort_by_app_size) -> compareBy<AppInfo> { it.size }
            getLocalizedString(R.string.sort_by_last_update) -> compareBy { it.lastUpdateTime }
            getLocalizedString(R.string.sort_by_install_date) -> compareBy { it.firstInstallTime }
            getLocalizedString(R.string.sort_by_target_version) -> compareBy { it.targetSdk }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName }
        }
        return if (isReverse) base.reversed() else base
    }

    private suspend fun getAppInfo(packageInfo: PackageInfo): AppInfo = withContext(Dispatchers.IO) {
        val appInfo = packageInfo.applicationInfo
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            packageInfo.versionCode
        }

        AppInfo(
            appName = packageManager.getApplicationLabel(appInfo).toString(),
            packageName = packageInfo.packageName,
            versionName = packageInfo.versionName ?: "",
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
        val prefs = PreferencesHelper(closeApp)
        return if (enableKeys.any { prefs.getBoolean(it + packageName, false) }) 1 else 0
    }

    private fun isSystemApp(appInfo: ApplicationInfo): Boolean =
        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    private fun getIsAppEnable(packageName: String): Int =
        if (packageManager.getApplicationEnabledSetting(packageName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) 1 else 0
}
