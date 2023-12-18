package com.close.hook.ads.data.repository;

import static com.close.hook.ads.util.AppUtils.isAppEnabled;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import com.close.hook.ads.data.model.AppInfo;
import com.close.hook.ads.hook.preference.PreferencesHelper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;

public class AppRepository {
	private final PackageManager packageManager;

	public AppRepository(PackageManager packageManager) {
		this.packageManager = packageManager;
	}

	public Observable<List<AppInfo>> getInstalledUserApps() {
		return getInstalledApps(false);
	}

	public Observable<List<AppInfo>> getInstalledSystemApps() {
		return getInstalledApps(true);
	}

	private Observable<List<AppInfo>> getInstalledApps(boolean isSystem) {
		return Observable.fromCallable(() -> {
			List<PackageInfo> packages = packageManager.getInstalledPackages(0);
			List<AppInfo> appList = new ArrayList<>();
			for (PackageInfo packageInfo : packages) {
				if (isSystemApp(packageInfo.applicationInfo) == isSystem) {
					appList.add(createAppInfo(packageInfo));
				}
			}
			appList.sort((app1, app2) -> app1.getAppName().compareToIgnoreCase(app2.getAppName()));
			return appList;
		})
		.subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .onErrorReturnItem(Collections.emptyList());
	}

	private boolean isSystemApp(ApplicationInfo applicationInfo) {
		return (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
	}

	private AppInfo createAppInfo(PackageInfo packageInfo) {
		String appName = getAppName(packageInfo.applicationInfo);
		Drawable appIcon = getAppIcon(packageInfo.applicationInfo);
		String versionName = getAppVersion(packageInfo);
		long size = new File(packageInfo.applicationInfo.sourceDir).length();
		int targetSdk = packageInfo.applicationInfo.targetSdkVersion;
        int isAppEnable = getIsAppEnable(packageInfo.packageName);
		int isEnable = isAppEnabled(packageInfo.packageName);
        return new AppInfo(appName, packageInfo.packageName, appIcon, versionName,
                packageInfo.firstInstallTime, packageInfo.lastUpdateTime, size, targetSdk, isAppEnable,
				isEnable);
    }

    private int getIsAppEnable(String packageName) {
        if (packageManager.getApplicationEnabledSetting(packageName) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            return 0;
        return 1;
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
