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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    private val prefsHelper by lazy { PreferencesHelper(closeApp) }

    private val enableKeys = arrayOf(
        "switch_one_", "switch_two_", "switch_three_", "switch_four_",
        "switch_five_", "switch_six_", "switch_seven_", "switch_eight_"
    )

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val refreshCounter = AtomicLong(0)
    private val _refreshTrigger = MutableStateFlow(refreshCounter.get())

    fun triggerRefresh() {
        _refreshTrigger.value = refreshCounter.incrementAndGet()
    }

    fun getAllAppsFlow(): Flow<List<AppInfo>> = _refreshTrigger
        .debounce(100L)
        .distinctUntilChanged()
        .flatMapLatest {
            flow {
                _isLoading.value = true
                val list = getAllApps()
                emit(list)
                _isLoading.value = false
            }
        }.flowOn(Dispatchers.IO)

    private suspend fun getAllApps(): List<AppInfo> = coroutineScope {
        val packages = runCatching {
            packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        }.getOrElse { emptyList() }

        packages.map { pkg ->
            async {
                runCatching { getAppInfo(pkg) }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }

    fun filterAndSortApps(apps: List<AppInfo>, filter: AppFilterState): List<AppInfo> {
        val now = System.currentTimeMillis()
        val comparator = getComparator(filter.filterOrder, filter.isReverse)
        return apps.asSequence()
            .filter {
                when (filter.appType) {
                    "user" -> !it.isSystem
                    "system" -> it.isSystem
                    "configured" -> it.isEnable == 1
                    else -> true
                }
            }
            .filter {
                (filter.keyword.isBlank() || it.appName.contains(filter.keyword, true) || it.packageName.contains(filter.keyword, true)) &&
                (!filter.showConfigured || it.isEnable == 1) &&
                (!filter.showUpdated || now - it.lastUpdateTime < 3 * 24 * 3600 * 1000L) &&
                (!filter.showDisabled || it.isAppEnable == 0)
            }
            .sortedWith(comparator)
            .toList()
    }

    private fun getAppInfo(pkg: PackageInfo): AppInfo {
        val appInfo = pkg.applicationInfo
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode.toInt() else pkg.versionCode
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
                       (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
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

    private fun getIsAppEnable(pkg: String): Int {
        return runCatching {
            packageManager.getApplicationEnabledSetting(pkg) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }.getOrDefault(true).let { if (it) 1 else 0 }
    }

    private fun isAppHooked(pkg: String): Int {
        return if (enableKeys.any { prefsHelper.getBoolean(it + pkg, false) }) 1 else 0
    }

    private fun getComparator(sortBy: Int, reverse: Boolean): Comparator<AppInfo> {
        val base = when (sortBy) {
            R.string.sort_by_app_size -> compareBy<AppInfo> { it.size }
            R.string.sort_by_last_update -> compareBy { it.lastUpdateTime }
            R.string.sort_by_install_date -> compareBy { it.firstInstallTime }
            R.string.sort_by_target_version -> compareBy { it.targetSdk }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName }
        }
        return if (reverse) base.reversed() else base
    }
}
