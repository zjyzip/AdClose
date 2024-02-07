package com.close.hook.ads.util;

import static com.close.hook.ads.CloseApplicationKt.closeApp;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.Window;
import android.widget.Toast;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.close.hook.ads.hook.preference.PreferencesHelper;

import java.util.Objects;

public class AppUtils {

    private static final String[] KEYS = {"switch_one_", "switch_two_", "switch_three_", "switch_four_", "switch_five_", "switch_six_" };

    public static Drawable getAppIcon(String packageName) {
        try {
            PackageManager pm = closeApp.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return info.loadIcon(pm);
        } catch (Exception e) {
            e.printStackTrace();

        }
        return null;
    }

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

    public static int getNavigationBarHeight(Context context) {
        DisplayMetrics metrics = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Objects.requireNonNull(context.getDisplay()).getMetrics(metrics);
        }
        int usableHeight = metrics.heightPixels;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getDisplay().getRealMetrics(metrics);
        }
        int realHeight = metrics.heightPixels;
        if (realHeight > usableHeight)
            return realHeight - usableHeight;
        return usableHeight;
    }

    public static void setSystemBarsColor(View view) {
        Context context = view.getContext();
        boolean darkMode = isDarkTheme(context);
        Activity activity = (Activity) context;
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.setNavigationBarDividerColor(Color.TRANSPARENT);
        }

        WindowInsetsControllerCompat windowInsetsController = new WindowInsetsControllerCompat(window, view);
        windowInsetsController.setAppearanceLightStatusBars(!darkMode);
        windowInsetsController.setAppearanceLightNavigationBars(!darkMode);
    }

    public static boolean isDarkTheme(Context context) {
        int currentNightMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES;
    }
}