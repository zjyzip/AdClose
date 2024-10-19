package com.close.hook.ads.hook;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import com.close.hook.ads.hook.gc.DisableClipboard;
import com.close.hook.ads.hook.gc.DisableFlagSecure;
import com.close.hook.ads.hook.gc.DisableShakeAd;
import com.close.hook.ads.hook.gc.HideEnvi;
import com.close.hook.ads.hook.gc.network.HideVPNStatus;
import com.close.hook.ads.hook.gc.network.RequestHook;
import com.close.hook.ads.hook.ha.AppAds;
import com.close.hook.ads.hook.ha.SDKAds;
import com.close.hook.ads.hook.ha.SDKAdsKit;
import com.close.hook.ads.hook.preference.PreferencesHelper;
import com.close.hook.ads.hook.util.ContextUtil;
import com.close.hook.ads.hook.util.HookUtil;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookInit implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String TAG = "com.close.hook.ads";

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        ContextUtil.initialize(() -> {
            XposedBridge.log("ContextUtil initialized");
        });
    }

    @SuppressLint("SuspiciousIndentation")
    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (shouldIgnorePackage(lpparam)) {
            return;
        }

        PreferencesHelper prefsHelper = new PreferencesHelper(TAG, "com.close.hook.ads_preferences");
        SettingsManager settingsManager = new SettingsManager(prefsHelper, lpparam.packageName);

        performHooking(lpparam, settingsManager);

        ContextUtil.addOnAppContextInitializedCallback(() -> {
            earlyHookComponents(ContextUtil.appContext, settingsManager);
        });
        ContextUtil.addOnInstrumentationContextInitializedCallback(() -> {
        });
        ContextUtil.addOnContextWrapperContextInitializedCallback(() -> {
        });
    }

    private void activateModule(XC_LoadPackage.LoadPackageParam lpparam) {
        HookUtil.hookSingleMethod(lpparam.classLoader, "com.close.hook.ads.ui.activity.MainActivity", "isModuleActivated", true);
    }

    private void performHooking(XC_LoadPackage.LoadPackageParam lpparam, SettingsManager settingsManager) {
        if (TAG.equals(lpparam.packageName)) {
            activateModule(lpparam);
        }

        applySettings(settingsManager);
    }

    private void applySettings(SettingsManager settingsManager) {
        if (settingsManager.isHideVPNStatusEnabled()) {
            HideVPNStatus.proxy();
        }

        if (settingsManager.isDisableClipboard()) {
            DisableClipboard.handle();
        }

        if (settingsManager.isDisableFlagSecureEnabled()) {
            DisableFlagSecure.process();
        }

        if (settingsManager.isHideEnivEnabled()) {
            HideEnvi.handle();
        }

        if (settingsManager.isDisableShakeAdEnabled()) {
            DisableShakeAd.handle();
        }
    }

    private void earlyHookComponents(Context appContext, SettingsManager settingsManager) {
        try {
            ClassLoader classLoader = appContext.getClassLoader();
            String packageName = appContext.getPackageName();
            CharSequence appName = getAppName(appContext, packageName);

            if (!TAG.equals(packageName)) {
                XposedBridge.log("Application Name: " + appName);
            }

            if (settingsManager.isRequestHookEnabled()) {
                RequestHook.init();
            }

            AppAds.progress(classLoader, packageName);

            if (settingsManager.isHandlePlatformAdEnabled()) {
                SDKAdsKit.INSTANCE.blockAds();
                SDKAds.hookAds(classLoader);
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + " Exception in earlyHookComponents: " + Log.getStackTraceString(e));
        }
    }

    private boolean shouldIgnorePackage(XC_LoadPackage.LoadPackageParam lpparam) {
        return lpparam.appInfo == null || !lpparam.isFirstApplication;
    }

    private CharSequence getAppName(Context context, String packageName) {
        try {
            ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(packageName, 0);
            return context.getPackageManager().getApplicationLabel(appInfo);
        } catch (PackageManager.NameNotFoundException e) {
            XposedBridge.log("Application Name Not Found for package: " + packageName);
            return packageName;
        }
    }
}
