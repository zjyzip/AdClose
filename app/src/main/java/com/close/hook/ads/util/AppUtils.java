package com.close.hook.ads.util;

import static com.close.hook.ads.CloseApplication.context;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.close.hook.ads.hook.preference.PreferencesHelper;

import java.util.Objects;

public class AppUtils {

    public static Drawable getAppIcon(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return info.loadIcon(pm);
        } catch (Exception e) {
            e.printStackTrace();

        }
        return null;
    }

    public static int isAppEnabled(String packageName) {
        PreferencesHelper prefsHelper = new PreferencesHelper(context, "com.close.hook.ads_preferences");
        String[] prefKeys = { "switch_one_", "switch_two_", "switch_three_", "switch_four_", "switch_five_",
                "switch_six_" };
        for (String prefKey : prefKeys) {
            if (prefsHelper.getBoolean(prefKey + packageName, false)) {
                return 1;
            }
        }
        return 0;
    }

    public static float dipToPixels(Context context, float dipValue) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dipValue, metrics);
    }

    public static void showToast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    public static void addSystemWindowInsetToPadding(View view, boolean left, boolean top, boolean right,
                                                     boolean bottom) {
        ViewCompat.setOnApplyWindowInsetsListener(view, new OnApplyWindowInsetsListener() {
            @NonNull
            @Override
            public WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat windowInsets) {
                Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                int paddingLeft, paddingTop, paddingRight, paddingBottom;
                if (left) {
                    paddingLeft = insets.left;
                } else {
                    paddingLeft = 0;
                }
                if (top) {
                    paddingTop = insets.top;
                } else {
                    paddingTop = 0;
                }
                if (right) {
                    paddingRight = insets.right;
                } else {
                    paddingRight = 0;
                }
                if (bottom) {
                    paddingBottom = insets.bottom;
                } else {
                    paddingBottom = 0;
                }
                v.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
                return windowInsets;
            }
        });
    }

    public static void addSystemWindowInsetToMargin(View view, boolean left, boolean top, boolean right,
                                                    boolean bottom) {
        ViewCompat.setOnApplyWindowInsetsListener(view, new OnApplyWindowInsetsListener() {
            @NonNull
            @Override
            public WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat windowInsets) {
                Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                int marginLeft, marginTop, marginRight, marginBottom;
                if (left) {
                    marginLeft = insets.left;
                } else {
                    marginLeft = 0;
                }
                if (top) {
                    marginTop = insets.top;
                } else {
                    marginTop = 0;
                }
                if (right) {
                    marginRight = insets.right;
                } else {
                    marginRight = 0;
                }
                if (bottom) {
                    marginBottom = insets.bottom;
                } else {
                    marginBottom = 0;
                }
                if (v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams p) {
                    p.setMargins(marginLeft, marginTop, marginRight, marginBottom);
                    v.requestLayout();
                }
                return windowInsets;
            }
        });
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