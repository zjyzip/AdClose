package com.close.hook.ads.data.repository;

import static com.close.hook.ads.util.AppUtils.isAppEnabled;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;

import com.close.hook.ads.data.model.AppInfo;
import com.close.hook.ads.ui.activity.MainActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;

public class AppRepository {
    private static final String TAG = "AppRepository";
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
        return Observable.fromCallable(() -> fetchInstalledApps(isSystem))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError(e -> Log.e(TAG, "Error fetching apps: ", e))
                .onErrorReturnItem(new ArrayList<>());
    }

    private List<AppInfo> fetchInstalledApps(boolean isSystem) {
        List<PackageInfo> allPackages = packageManager.getInstalledPackages(0);
        List<AppInfo> appList = new ArrayList<>();
        for (PackageInfo packageInfo : allPackages) {
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
        String appName = packageManager.getApplicationLabel(packageInfo.applicationInfo).toString();
        Drawable appIcon = packageManager.getApplicationIcon(packageInfo.applicationInfo);
        long size = new File(packageInfo.applicationInfo.sourceDir).length();
        int versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? (int) packageInfo.getLongVersionCode() : packageInfo.versionCode;

        return new AppInfo(
            appName,
            packageInfo.packageName,
            appIcon,
            packageInfo.versionName,
            versionCode,
            packageInfo.firstInstallTime,
            packageInfo.lastUpdateTime,
            size,
            packageInfo.applicationInfo.targetSdkVersion,
            getIsAppEnable(packageInfo.packageName),
            MainActivity.isModuleActivated() ? isAppEnabled(packageInfo.packageName) : 0
        );
    }

    private int getIsAppEnable(String packageName) {
        return packageManager.getApplicationEnabledSetting(packageName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED ? 1 : 0;
    }
}
