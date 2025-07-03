package com.close.hook.ads.data.repository

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.close.hook.ads.R
import com.close.hook.ads.closeApp
import com.close.hook.ads.data.model.AppFilterState
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.ui.activity.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

class AppRepository(private val packageManager: PackageManager) {

    private val prefsHelper by lazy { HookPrefs(closeApp) }

    private val enableKeys = arrayOf(
        "switch_one_", "switch_two_", "switch_three_", "switch_four_",
        "switch_five_", "switch_six_", "switch_seven_", "switch_eight_"
    )

    fun getAllAppsFlow(): Flow<List<AppInfo>> = flow {
        val pkgs = runCatching {
            packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        }.getOrElse { emptyList() }

        val modActive = MainActivity.isModuleActivated()

        val result = coroutineScope {
            pkgs.map { pkg ->
                async(Dispatchers.Default) {
                    val app = pkg.applicationInfo
                    val verCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode.toInt() else pkg.versionCode
                    val isSys = (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) || (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                    val isEn = packageManager.getApplicationEnabledSetting(pkg.packageName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    val enabled = if (modActive && enableKeys.any { prefsHelper.getBoolean(it + pkg.packageName, false) }) 1 else 0
                    AppInfo(
                        appName = app.loadLabel(packageManager).toString(),
                        packageName = pkg.packageName,
                        versionName = pkg.versionName.orEmpty(),
                        versionCode = verCode,
                        firstInstallTime = pkg.firstInstallTime,
                        lastUpdateTime = pkg.lastUpdateTime,
                        size = File(app.sourceDir).length(),
                        targetSdk = app.targetSdkVersion,
                        minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) app.minSdkVersion else 0,
                        isAppEnable = if (isEn) 1 else 0,
                        isEnable = enabled,
                        isSystem = isSys
                    )
                }
            }.map { it.await() }
        }

        emit(result)
    }.flowOn(Dispatchers.IO)

    fun filterAndSortApps(apps: List<AppInfo>, filter: AppFilterState): List<AppInfo> {
        val now = System.currentTimeMillis()
        val comp = getComparator(filter.filterOrder, filter.isReverse)
        return apps.asSequence()
            .filter { app ->
                when (filter.appType) {
                    "user" -> !app.isSystem
                    "system" -> app.isSystem
                    "configured" -> app.isEnable == 1
                    else -> true
                }
            }
            .filter { app ->
                (filter.keyword.isBlank() || app.appName.contains(filter.keyword, true) || app.packageName.contains(filter.keyword, true)) &&
                (!filter.showConfigured || app.isEnable == 1) &&
                (!filter.showUpdated || now - app.lastUpdateTime < 259200000L) &&
                (!filter.showDisabled || app.isAppEnable == 0)
            }
            .sortedWith(comp)
            .toList()
    }

    private fun getComparator(sortBy: Int, reverse: Boolean): Comparator<AppInfo> {
        val c = when (sortBy) {
            R.string.sort_by_app_size -> compareBy<AppInfo> { it.size }
            R.string.sort_by_last_update -> compareBy { it.lastUpdateTime }
            R.string.sort_by_install_date -> compareBy { it.firstInstallTime }
            R.string.sort_by_target_version -> compareBy { it.targetSdk }
            else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.appName }
        }
        return if (reverse) c.reversed() else c
    }
}
