package com.close.hook.ads.data.repository

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.ui.activity.MainActivity
import com.close.hook.ads.util.AppUtils
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.io.File

class AppRepository(private val packageManager: PackageManager) {

    companion object {
        private const val TAG = "AppRepository"
    }

    fun getInstalledUserApps(): Observable<List<AppInfo>> = getInstalledApps(false)

    fun getInstalledSystemApps(): Observable<List<AppInfo>> = getInstalledApps(true)

    private fun getInstalledApps(isSystem: Boolean): Observable<List<AppInfo>> =
        Observable.fromCallable { fetchInstalledApps(isSystem) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnError { e -> Log.e(TAG, "Error fetching apps: ", e) }
            .onErrorReturnItem(ArrayList())

    private fun fetchInstalledApps(isSystem: Boolean): List<AppInfo> {
        val allPackages = packageManager.getInstalledPackages(0)
        return allPackages.filter {
            isSystemApp(it.applicationInfo) == isSystem
        }.map { packageInfo ->
            createAppInfo(packageInfo)
        }.sortedBy { it.appName.toLowerCase() }
    }

    private fun isSystemApp(applicationInfo: ApplicationInfo): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0

    private fun createAppInfo(packageInfo: android.content.pm.PackageInfo): AppInfo {
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

        return AppInfo(
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
        )
    }

    private fun getIsAppEnable(packageName: String): Int =
        if (packageManager.getApplicationEnabledSetting(packageName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) 1 else 0
}
