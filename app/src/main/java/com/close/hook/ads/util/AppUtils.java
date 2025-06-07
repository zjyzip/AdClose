package com.close.hook.ads.util;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.widget.Toast;

public class AppUtils {

    public static void showToast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    public static CharSequence getAppName(Context context, String packageName) {
        PackageManager pm = context.getPackageManager();
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(appInfo);
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    public static boolean isMainProcess(Context context) {
        int pid = Process.myPid();
        String mainProcess = context.getPackageName();
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

        if (manager != null) {
            for (ActivityManager.RunningAppProcessInfo proc : manager.getRunningAppProcesses()) {
                if (proc.pid == pid && mainProcess.equals(proc.processName)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void showHookTip(Context context, String packageName) {
        showToast(context, "AdClose Hooking into " + getAppName(context, packageName));
    }
}
