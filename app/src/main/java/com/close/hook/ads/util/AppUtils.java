package com.close.hook.ads.util;

import static com.close.hook.ads.CloseApplicationKt.closeApp;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.widget.Toast;

import com.close.hook.ads.hook.preference.PreferencesHelper;

public class AppUtils {

    private static final String[] KEYS = {
        "switch_one_", "switch_two_", "switch_three_", "switch_four_",
        "switch_five_", "switch_six_", "switch_seven_", "switch_eight_"
    };

    public static int isAppEnabled(String packageName) {
        PreferencesHelper prefsHelper = new PreferencesHelper(closeApp, "com.close.hook.ads_preferences");
        for (String prefKey : KEYS) {
            if (prefsHelper.getBoolean(prefKey + packageName, false)) {
                return 1;
            }
        }
        return 0;
    }

    public static void showToast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    public static CharSequence getAppName(Context context, String packageName) {
        try {
            ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(packageName, 0);
            return context.getPackageManager().getApplicationLabel(appInfo);
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    public static boolean isMainProcess(Context context) {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        String mainProcessName = context.getPackageName();
        int pid = Process.myPid();

        for (ActivityManager.RunningAppProcessInfo appProcess : activityManager.getRunningAppProcesses()) {
            if (appProcess.pid == pid && mainProcessName.equals(appProcess.processName)) {
                return true;
            }
        }
        return false;
    }

    public static void showHookTip(Context context, String packageName) {
        CharSequence appName = getAppName(context, packageName);
        showToast(context, "AdClose Hooking into " + appName);
    }
}
