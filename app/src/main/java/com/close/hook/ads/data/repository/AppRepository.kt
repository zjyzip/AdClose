package com.close.hook.ads.data.repository

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.close.hook.ads.R
import com.close.hook.ads.data.model.AppFilterState
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.manager.ServiceManager
import com.close.hook.ads.preference.HookPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.text.Collator
import java.util.Locale

class AppRepository(private val packageManager: PackageManager) {

    private val enableKeys = arrayOf(
        "switch_one_", "switch_two_", "switch_three_", "switch_four_",
        "switch_five_", "switch_six_", "switch_seven_", "switch_eight_",
        "switch_nine_",
        "overall_hook_enabled_"
    )

    private val SORT_OPTIONS = listOf(
        R.string.sort_by_app_name,
        R.string.sort_by_app_size,
        R.string.sort_by_last_update,
        R.string.sort_by_install_date,
        R.string.sort_by_target_version
    )

    fun getAllAppsFlow(): Flow<List<AppInfo>> = flow {
        val pkgs = runCatching {
            packageManager.getInstalledPackages(0)
        }.getOrElse { emptyList() }

        val modActive = ServiceManager.isModuleActivated
        val allPrefs = HookPrefs.getAll()

        val appList = coroutineScope {
            pkgs.map { pkg ->
                async(Dispatchers.IO) {
                    val app = pkg.applicationInfo ?: return@async null
                    
                    val verCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pkg.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        pkg.versionCode
                    }
                    
                    val isSys = (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) || 
                                (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                    
                    val isEn = try {
                        packageManager.getApplicationEnabledSetting(pkg.packageName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    } catch (e: IllegalArgumentException) {
                        false
                    }

                    val enabled = if (modActive) {
                        var isHooked = 0
                        for (key in enableKeys) {
                            if (allPrefs["$key${pkg.packageName}"] == true) {
                                isHooked = 1
                                break
                            }
                        }
                        isHooked
                    } else 0

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
            }.mapNotNull { it.await() }
        }

        emit(appList)
    }.flowOn(Dispatchers.IO)

    fun filterAndSortApps(apps: List<AppInfo>, filter: AppFilterState): List<AppInfo> {
        val now = System.currentTimeMillis()
        val recentUpdateThreshold = 30L * 24 * 60 * 60 * 1000 
        
        val sortByResId = SORT_OPTIONS.getOrElse(filter.filterOrder) { R.string.sort_by_app_name }
        val comp = getComparator(sortByResId, filter.isReverse)

        val keyword = filter.keyword.lowercase()
        val hasKeyword = keyword.isNotBlank()

        return apps.asSequence()
            .filter { app ->
                val typeMatch = when (filter.appType) {
                    "all" -> true
                    "user" -> !app.isSystem
                    "system" -> app.isSystem
                    "configured" -> app.isEnable == 1
                    else -> true
                }
                if (!typeMatch) return@filter false

                if (hasKeyword) {
                    if (!app.appName.contains(keyword, true) && 
                        !app.packageName.contains(keyword, true)) {
                        return@filter false
                    }
                }

                if (filter.showConfigured && app.isEnable != 1) return@filter false
                if (filter.showUpdated && (now - app.lastUpdateTime > recentUpdateThreshold)) return@filter false
                if (filter.showDisabled && app.isAppEnable != 0) return@filter false

                true
            }
            .sortedWith(comp)
            .toList()
    }

    private fun getComparator(sortBy: Int, reverse: Boolean): Comparator<AppInfo> {
        val collator = Collator.getInstance(Locale.getDefault())
        val comparator = when (sortBy) {
            R.string.sort_by_app_size -> compareBy<AppInfo> { it.size }
            R.string.sort_by_last_update -> compareBy { it.lastUpdateTime }
            R.string.sort_by_install_date -> compareBy { it.firstInstallTime }
            R.string.sort_by_target_version -> compareBy { it.targetSdk }
            else -> Comparator { o1, o2 -> collator.compare(o1.appName, o2.appName) }
        }
        return if (reverse) comparator.reversed() else comparator
    }
}
