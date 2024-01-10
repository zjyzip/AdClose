package com.close.hook.ads.data.repository;

import static com.close.hook.ads.util.AppUtils.isAppEnabled;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import com.close.hook.ads.data.model.AppInfo;
import com.close.hook.ads.ui.activity.MainActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

public class AppRepository {
    private final PackageManager packageManager;
    private final Cache<Boolean, List<AppInfo>> appsCache;

    public AppRepository(PackageManager packageManager) {
        this.packageManager = packageManager;
        this.appsCache = CacheBuilder.newBuilder()
                        .maximumSize(2)
                        .expireAfterAccess(1, TimeUnit.HOURS)
                        .build();
    }

    public Observable<List<AppInfo>> getInstalledUserApps() {
        return getInstalledApps(false);
    }

    public Observable<List<AppInfo>> getInstalledSystemApps() {
        return getInstalledApps(true);
    }

    private Observable<List<AppInfo>> getInstalledApps(boolean isSystem) {
        return Observable.fromCallable(() -> {
            List<AppInfo> cachedApps = appsCache.getIfPresent(isSystem);
            if (cachedApps != null) {
                return cachedApps;
            }
            List<AppInfo> appList = fetchInstalledApps(isSystem);
            appsCache.put(isSystem, appList);
            return appList;
        })
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .onErrorReturnItem(Collections.emptyList());
    }

    private List<AppInfo> fetchInstalledApps(boolean isSystem) {
        List<PackageInfo> packages = packageManager.getInstalledPackages(0);
        List<AppInfo> appList = new ArrayList<>();
        for (PackageInfo packageInfo : packages) {
            if (isSystemApp(packageInfo.applicationInfo) == isSystem) {
                appList.add(createAppInfo(packageInfo));
            }
        }
        appList.sort((app1, app2) -> app1.getAppName().compareToIgnoreCase(app2.getAppName()));
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
