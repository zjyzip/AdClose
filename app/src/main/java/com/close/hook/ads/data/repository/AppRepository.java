package com.close.hook.ads.data.repository;

import static com.close.hook.ads.CloseApplication.context;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import com.close.hook.ads.data.model.AppInfo;
import com.close.hook.ads.hook.preference.PreferencesHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class AppRepository {
    private final PackageManager packageManager;

    public AppRepository(PackageManager packageManager) {
        this.packageManager = packageManager;
    }

    // 获取已安装用户应用
    public Observable<List<AppInfo>> getInstalledUserApps() {
        return getInstalledApps(false);
    }

    // 获取已安装系统应用
    public Observable<List<AppInfo>> getInstalledSystemApps() {
        return getInstalledApps(true);
    }

    // 获取已安装应用（用户应用或系统应用）
    public Observable<List<AppInfo>> getInstalledApps(boolean isSystem) {
        return Observable.fromCallable(
                        () -> {
                            List<AppInfo> appList = new ArrayList<>();
                            List<PackageInfo> packages = packageManager.getInstalledPackages(0);
                            for (PackageInfo packageInfo : packages) {
                                if (isSystemApp(packageInfo.applicationInfo) == isSystem) {
                                    appList.add(buildAppInfo(packageInfo));
                                }
                            }
                            Collections.sort(
                                    appList,
                                    (app1, app2) -> app1.getAppName().compareToIgnoreCase(app2.getAppName()));
                            return appList;
                        })
                .subscribeOn(Schedulers.io());
    }

    // 获取应用名称
    public String getAppName(ApplicationInfo appInfo) {
        return packageManager.getApplicationLabel(appInfo).toString();
    }

    // 获取应用图标
    public Drawable getAppIcon(ApplicationInfo appInfo) {
        return packageManager.getApplicationIcon(appInfo);
    }

    // 获取应用版本
    public String getAppVersion(PackageInfo packageInfo) {
        return packageInfo.versionName;
    }

    // 判断是否为系统应用
    private boolean isSystemApp(ApplicationInfo applicationInfo) {
        return (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    // 构建 AppInfo 对象
    private AppInfo buildAppInfo(PackageInfo packageInfo) {
        String appName = getAppName(packageInfo.applicationInfo);
        Drawable appIcon = getAppIcon(packageInfo.applicationInfo);
        String versionName = getAppVersion(packageInfo);
        Long firstInstallTime = packageInfo.firstInstallTime;
        Long lastUpdateTime = packageInfo.lastUpdateTime;
        int isEnable = 0;
        PreferencesHelper prefsHelper = new PreferencesHelper(context, "com.close.hook.ads_preferences");
        String[] prefKeys = {"switch_one_", "switch_two_", "switch_three_", "switch_four_", "switch_five_",
                "switch_six_", "switch_seven_"};
        for (String prefKey : prefKeys) {
            String key = prefKey + packageInfo.packageName;
            if (prefsHelper.getBoolean(key, false)) {
                isEnable = 1;
                break;
            }
        }
        return new AppInfo(appName, packageInfo.packageName, appIcon, versionName, isEnable, firstInstallTime, lastUpdateTime);
    }
}
