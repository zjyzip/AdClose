package com.close.hook.ads.data.repository;

import static com.close.hook.ads.CloseApplication.context;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import com.close.hook.ads.data.model.AppInfo;
import com.close.hook.ads.hook.preference.PreferencesHelper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

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
		}).subscribeOn(Schedulers.io());
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
		int isEnable = isAppEnabled(packageInfo.packageName);
		return new AppInfo(appName, packageInfo.packageName, appIcon, versionName, isEnable,
				packageInfo.firstInstallTime, packageInfo.lastUpdateTime, size, targetSdk);
	}

	private int isAppEnabled(String packageName) {
		PreferencesHelper prefsHelper = new PreferencesHelper(context, "com.close.hook.ads_preferences");
		String[] prefKeys = { "switch_one_", "switch_two_", "switch_three_", "switch_four_", "switch_five_",
				"switch_six_", "switch_seven_" };
		for (String prefKey : prefKeys) {
			if (prefsHelper.getBoolean(prefKey + packageName, false)) {
				return 1;
			}
		}
		return 0;
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
