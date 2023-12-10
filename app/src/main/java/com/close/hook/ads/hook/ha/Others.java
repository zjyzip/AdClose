package com.close.hook.ads.hook.ha;

import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import android.os.Bundle;
import java.util.Properties;

public class Others {

	private static boolean shouldModifyBundle = false;

	public static void handle(XC_LoadPackage.LoadPackageParam lpparam) {

		if (lpparam.packageName.equals("com.mfcloudcalculate.networkdisk")) { // 123云盘

			XposedHelpers.findAndHookMethod("com.mfcloudcalculate.networkdisk.MyApplication", lpparam.classLoader,
					"onCreate", new XC_MethodHook() {
						@Override
						protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
							shouldModifyBundle = true;
						}

						@Override
						protected void afterHookedMethod(MethodHookParam param) throws Throwable {
							shouldModifyBundle = false;
						}
					});

			XposedHelpers.findAndHookMethod(Bundle.class, "putBoolean", String.class, boolean.class,
					new XC_MethodHook() {
						@Override
						protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
							if (shouldModifyBundle && "mCallNativeDefaultHandler".equals(param.args[0])) {
								param.args[1] = false;
							}
						}
					});
		}

		if (lpparam.packageName.equals("com.qiyi.video") || lpparam.packageName.equals("com.qiyi.video.pad")) { // 爱奇艺
			XposedBridge.hookAllMethods(Properties.class, "getProperty", new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					if ("qiyi.export.key".equals(param.args[0])) {
						param.setResult("59e36a5e70e4c4efc6fcbc4db7ea59c1");
					}
				}
			});
		}
	}

}
