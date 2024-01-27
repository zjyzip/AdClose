package com.close.hook.ads.hook.ha;

import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.hook.util.HookUtil.HookInfo;

import androidx.collection.ArrayMap;

import de.robv.android.xposed.*;

public class AppAds {

	public static void progress(ClassLoader classLoader, String packageName) {
		ArrayMap<String, HookInfo[]> appHooks = new ArrayMap<>();

		appHooks.put("应用包名", new HookInfo[] { // 应用
				new HookInfo("类名", "方法名", 1),
				new HookInfo("类名", "方法名", null),
				new HookInfo("类名", "方法名", false),
				new HookInfo("类名", new String[] { "方法1", "方法2" }, null) });

		// 根据传入的packageName执行相应的hook
		if (appHooks.containsKey(packageName)) {
			for (HookInfo hookInfo : appHooks.get(packageName)) {
				HookUtil.hookMethods(classLoader, hookInfo.className, hookInfo.methodNames, hookInfo.returnValue);
			}
		}

		if (packageName.equals("com.weico.international")) { // 微博轻享版
			WeiboIE.handle(classLoader);
		}

	}

}
