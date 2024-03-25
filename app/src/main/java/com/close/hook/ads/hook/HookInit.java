package com.close.hook.ads.hook;

import android.util.Log;
import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.close.hook.ads.hook.gc.DisableFlagSecure;
import com.close.hook.ads.hook.gc.DisableShakeAd;
import com.close.hook.ads.hook.gc.HideEnvi;
import com.close.hook.ads.hook.gc.network.HideVPNStatus;
import com.close.hook.ads.hook.gc.network.RequestHook;
import com.close.hook.ads.hook.ha.AppAds;
import com.close.hook.ads.hook.ha.SDKAds;
import com.close.hook.ads.hook.ha.SDKAdsKit;
import com.close.hook.ads.hook.preference.PreferencesHelper;
import com.close.hook.ads.ui.activity.MainActivity;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookInit implements IXposedHookLoadPackage {
	private static final String TAG = "com.close.hook.ads";
	public static Context globalContext;

	@SuppressLint("SuspiciousIndentation")
	@Override
	public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
		if (shouldIgnorePackage(lpparam)) {
			return;
		}

		performHooking(lpparam);
	}

	private void activateModule(XC_LoadPackage.LoadPackageParam lpparam) {
		XposedHelpers.findAndHookMethod(MainActivity.class.getName(), lpparam.classLoader, "isModuleActivated",
				XC_MethodReplacement.returnConstant(true));
	}

	private void performHooking(XC_LoadPackage.LoadPackageParam lpparam) {
		if (TAG.equals(lpparam.packageName)) {
			activateModule(lpparam);
		}

		PreferencesHelper prefsHelper = new PreferencesHelper(TAG, "com.close.hook.ads_preferences");
		SettingsManager settingsManager = new SettingsManager(prefsHelper, lpparam.packageName);

		applySettings(settingsManager);
	}

	private void applySettings(SettingsManager settingsManager) {
		if (settingsManager.isHideVPNStatusEnabled()) {
			HideVPNStatus.proxy();
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

		try {
			XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) {
					globalContext = (Context) param.args[0];
					ClassLoader classLoader = globalContext.getClassLoader();

					String packageName = globalContext.getPackageName();
					CharSequence appName = getAppName(globalContext, packageName);

					if (!TAG.equals(packageName)) {
		//				XposedBridge.log("found classload is => " + classLoader.toString());
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
				}
			});
		} catch (Exception e) {
			XposedBridge.log(TAG + " Exception in handleLoadPackage: " + Log.getStackTraceString(e));
		}
	}

	private boolean shouldIgnorePackage(XC_LoadPackage.LoadPackageParam lpparam) {
		return lpparam.appInfo == null || !lpparam.isFirstApplication;
	}

	private CharSequence getAppName(Context context, String packageName) {
		PackageManager packageManager = context.getPackageManager();
		try {
			ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
			return packageManager.getApplicationLabel(appInfo);
		} catch (PackageManager.NameNotFoundException e) {
			XposedBridge.log("Application Name Not Found for package: " + packageName);
			return null;
		}
	}

}