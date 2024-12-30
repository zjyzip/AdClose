package com.close.hook.ads.hook;

import android.annotation.SuppressLint;
import android.content.Context;
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
import com.close.hook.ads.util.AppUtils;
import com.close.hook.ads.hook.util.ContextUtil;
import com.close.hook.ads.hook.util.DexDumpUtil;
import com.close.hook.ads.hook.util.HookUtil;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookInit implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "com.close.hook.ads";
    private static final String PREFS_NAME = "com.close.hook.ads_preferences";

    private static final boolean ENABLE_DEX_DUMP = false;

    private SettingsManager settingsManager;

    @Override
    public void initZygote(StartupParam startupParam) {
        ContextUtil.initialize(() -> {
            XposedBridge.log("HookInit | ContextUtil initialized.");
        });
    }

    @SuppressLint("SuspiciousIndentation")
    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (shouldIgnorePackage(lpparam)) {
                return;
            }

            if (TAG.equals(lpparam.packageName)) {
                activateModule(lpparam);
            }

            PreferencesHelper prefsHelper = new PreferencesHelper(TAG, PREFS_NAME);
            settingsManager = new SettingsManager(prefsHelper, lpparam.packageName);

            applySettings(settingsManager);

            registerContextCallbacks();
        } catch (Exception e) {
            XposedBridge.log("Error in handleLoadPackage: " + e.getMessage());
        }
    }

    private void activateModule(XC_LoadPackage.LoadPackageParam lpparam) {
        HookUtil.hookSingleMethod(lpparam.classLoader, "com.close.hook.ads.ui.activity.MainActivity", "isModuleActivated", true);
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

    private void setupApplicationHooks(Context applicationContext) {
        try {
            ClassLoader classLoader = applicationContext.getClassLoader();
            String packageName = applicationContext.getPackageName();
            CharSequence appName = AppUtils.getAppName(applicationContext, packageName);

            if (!TAG.equals(packageName)) {
                if (ENABLE_DEX_DUMP) {
                    DexDumpUtil.INSTANCE.dumpDexFilesByPackageName(packageName);
                }

                if (AppUtils.isMainProcess(applicationContext)) {
                    AppUtils.showHookTip(applicationContext, packageName);
                }

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
            XposedBridge.log(TAG + " Exception in setupApplicationHooks: " + Log.getStackTraceString(e));
        }
    }

    private void registerContextCallbacks() {
        ContextUtil.addOnActivityThreadContextInitializedCallback(() -> {
        });

        ContextUtil.addOnApplicationContextInitializedCallback(() -> {
            setupApplicationHooks(ContextUtil.applicationContext);
        });

        ContextUtil.addOnContextWrapperContextInitializedCallback(() -> {
        });

        ContextUtil.addOnInstrumentationContextInitializedCallback(() -> {
        });
    }

    private boolean shouldIgnorePackage(XC_LoadPackage.LoadPackageParam lpparam) {
        return lpparam.appInfo == null || !lpparam.isFirstApplication;
    }
}
