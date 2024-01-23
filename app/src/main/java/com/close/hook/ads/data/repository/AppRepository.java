package com.close.hook.ads.data.repository;

import static com.close.hook.ads.util.AppUtils.isAppEnabled;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.Log;

import com.close.hook.ads.data.model.AppInfo;
import com.close.hook.ads.ui.activity.MainActivity;

import java.io.File;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

public class AppRepository {
    private static final String TAG = "AppRepository";
    private final PackageManager packageManager;
    private final Cache<AppType, List<AppInfo>> appsCache;
    private List<PackageInfo> allPackages;

    public AppRepository(PackageManager packageManager) {
        this.packageManager = packageManager;
        this.appsCache = CacheBuilder.newBuilder()
                        .maximumSize(2)
                        .expireAfterAccess(1, TimeUnit.HOURS)
                        .build();
        fetchAllPackageInfo();
    }

    private void fetchAllPackageInfo() {
        allPackages = packageManager.getInstalledPackages(0);
    }

    public Observable<List<AppInfo>> getInstalledUserApps() {
        return getInstalledApps(AppType.USER);
    }

    public Observable<List<AppInfo>> getInstalledSystemApps() {
        return getInstalledApps(AppType.SYSTEM);
    }

    private Observable<List<AppInfo>> getInstalledApps(AppType appType) {
        return Observable.fromCallable(() -> {
            List<AppInfo> cachedApps = appsCache.getIfPresent(appType);
            if (cachedApps != null) {
                return cachedApps;
            }
            List<AppInfo> appList = fetchInstalledApps(appType == AppType.SYSTEM);
            appList.sort((app1, app2) -> app1.getAppName().compareToIgnoreCase(app2.getAppName()));
            appsCache.put(appType, appList);
            return appList;
        })
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .doOnError(e -> Log.e(TAG, "Error fetching apps: ", e))
        .onErrorReturnItem(Collections.emptyList());
    }

    private List<AppInfo> fetchInstalledApps(boolean isSystem) {
        List<AppInfo> appList = new LinkedList<>();
        for (PackageInfo packageInfo : allPackages) {
            if (isSystemApp(packageInfo.applicationInfo) == isSystem) {
                appList.add(createAppInfo(packageInfo));
            }
        }
        return appList;
    }

    private boolean isSystemApp(ApplicationInfo applicationInfo) {
        return (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

	private AppInfo createAppInfo(PackageInfo packageInfo) {
		String appName = getAppName(packageInfo.applicationInfo);
		String versionName = getAppVersion(packageInfo);
		int versionCode = packageInfo.versionCode;
		Drawable appIcon = getAppIcon(packageInfo.applicationInfo);
		long size = new File(packageInfo.applicationInfo.sourceDir).length();
		int targetSdk = packageInfo.applicationInfo.targetSdkVersion;
        int isAppEnable = getIsAppEnable(packageInfo.packageName);
		int isEnable = (MainActivity.isModuleActivated()) ? isAppEnabled(packageInfo.packageName) : 0;

        return new AppInfo(appName, packageInfo.packageName, appIcon, versionName, versionCode,
                packageInfo.firstInstallTime, packageInfo.lastUpdateTime, size, targetSdk, isAppEnable,
				isEnable);
    }

    private int getIsAppEnable(String packageName) {
        return packageManager.getApplicationEnabledSetting(packageName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED ? 1 : 0;
    }

	private String getAppName(ApplicationInfo appInfo) {
		return packageManager.getApplicationLabel(appInfo).toString();
	}

	private Drawable getAppIcon(ApplicationInfo appInfo) {
		return packageManager.getApplicationIcon(appInfo);
	}

	private String getAppVersion(PackageInfo packageInfo) {
		return packageInfo.versionName;
	}

}

enum AppType {
    USER,
    SYSTEM
}
