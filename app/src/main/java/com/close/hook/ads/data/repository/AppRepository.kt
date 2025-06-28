package com.close.hook.ads.data.repository

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.close.hook.ads.R
import com.close.hook.ads.closeApp
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.data.model.AppFilterState
import com.close.hook.ads.hook.preference.PreferencesHelper
import com.close.hook.ads.ui.activity.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class AppRepository(private val packageManager: PackageManager) {

    private val enableKeys = arrayOf(
        "switch_one_", "switch_two_", "switch_three_", "switch_four_",
        "switch_five_", "switch_six_", "switch_seven_", "switch_eight_"
    )

    private val prefsHelper: PreferencesHelper by lazy {
        PreferencesHelper(closeApp)
    }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val refreshCounter = AtomicLong(0)
    private val _refreshTrigger = MutableStateFlow(refreshCounter.get())

    fun triggerRefresh() {
        _refreshTrigger.value = refreshCounter.incrementAndGet()
    }

    fun getAllAppsFlow(): Flow<List<AppInfo>> = _refreshTrigger
        .debounce(300L)
        .distinctUntilChanged()
        .flatMapLatest {
            flow {
                _isLoading.value = true

                val packages = runCatching {
                    packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
                }.getOrElse { emptyList() }

                val appInfos = packages.mapNotNull {
                    runCatching { getAppInfo(it) }.getOrNull()
                }

                emit(appInfos)
                _isLoading.value = false
            }
        }.flowOn(Dispatchers.IO)

    fun filterAndSortApps(apps: List<AppInfo>, filterState: AppFilterState): List<AppInfo> {
        val now = System.currentTimeMillis()
        val comparator = getComparator(filterState.filterOrder, filterState.isReverse)

        return apps.asSequence()
            .filter { app ->
                when (filterState.appType) {
                    "user" -> !app.isSystem
                    "system" -> app.isSystem
                    "configured" -> app.isEnable == 1
                    else -> true
                }
            }
            .filter { app ->
                matchesKeyword(app, filterState.keyword) &&
                matchesFilter(app, filterState, now)
            }
            .sortedWith(comparator)
            .toList()
    }

    private fun getAppInfo(pkg: PackageInfo): AppInfo {
        val appInfo = pkg.applicationInfo
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pkg.longVersionCode.toInt()
        else
            @Suppress("DEPRECATION") pkg.versionCode

        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                       (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

        return AppInfo(
            appName = appInfo.loadLabel(packageManager).toString(),
            packageName = pkg.packageName,
            versionName = pkg.versionName.orEmpty(),
            versionCode = versionCode,
            firstInstallTime = pkg.firstInstallTime,
            lastUpdateTime = pkg.lastUpdateTime,
            size = runCatching { File(appInfo.sourceDir).length() }.getOrDefault(0L),
            targetSdk = appInfo.targetSdkVersion,
            minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 0,
            isAppEnable = getIsAppEnable(pkg.packageName),
            isEnable = if (MainActivity.isModuleActivated()) isAppHooked(pkg.packageName) else 0,
            isSystem = isSystem
        )
    }

    private fun getIsAppEnable(packageName: String): Int {
        return runCatching {
            val state = packageManager.getApplicationEnabledSetting(packageName)
            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }.getOrDefault(true).let { if (it) 1 else 0 }
    }

    private fun isAppHooked(packageName: String): Int {
        return if (enableKeys.any { prefsHelper.getBoolean(it + packageName, false) }) 1 else 0
    }

    private fun matchesKeyword(app: AppInfo, keyword: String): Boolean {
        return keyword.isBlank() ||
                app.appName.contains(keyword, ignoreCase = true) ||
                app.packageName.contains(keyword, ignoreCase = true)
    }

    private fun matchesFilter(app: AppInfo, state: AppFilterState, now: Long): Boolean {
        if (state.showConfigured && app.isEnable != 1) return false
        if (state.showUpdated && now - app.lastUpdateTime >= 3 * 24 * 3600 * 1000L) return false
        if (state.showDisabled && app.isAppEnable != 0) return false
        return true
    }

    private fun getComparator(sortBy: Int, isReverse: Boolean): Comparator<AppInfo> {
        val base = when (sortBy) {
            R.string.sort_by_app_size -> compareBy<AppInfo> { it.size }
            R.string.sort_by_last_update -> compareBy { it.lastUpdateTime }
            R.string.sort_by_install_date -> compareBy { it.firstInstallTime }
            R.string.sort_by_target_version -> compareBy { it.targetSdk }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName }
        }
        return if (isReverse) base.reversed() else base
    }
}
