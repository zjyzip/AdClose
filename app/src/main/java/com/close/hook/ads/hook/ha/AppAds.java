package com.close.hook.ads.hook.ha;

import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.hook.util.HookUtil.HookInfo;

import androidx.collection.ArrayMap;

import java.util.Properties;
import de.robv.android.xposed.*;

public class AppAds {

    public static void progress(ClassLoader classLoader, String packageName) {
        // 初始化appHooks映射，每个应用对应一组需要hook的信息
        ArrayMap<String, HookInfo[]> appHooks = getAppHooks();

        // 根据传入的packageName执行相应的hook
        if (appHooks.containsKey(packageName)) {
            for (HookInfo hookInfo : appHooks.get(packageName)) {
                HookUtil.hookMethods(classLoader, hookInfo.className, hookInfo.methodNames, hookInfo.returnValue);
            }
        }

        // 特定包名的特定处理逻辑
        if (packageName.equals("com.weico.international")) {
            WeiboIE.handle(classLoader);
        }

    }

    // 初始化appHooks
    private static ArrayMap<String, HookInfo[]> getAppHooks() {
        ArrayMap<String, HookInfo[]> appHooks = new ArrayMap<>();

        // 应用1
		appHooks.put("应用包名", new HookInfo[] {
			new HookInfo("类名", "方法名", 1),
			new HookInfo("类名", "方法名", null),
			new HookInfo("类名", "方法名", false),
			new HookInfo("类名", new String[] { "方法1", "方法2" }, null) 
		});

        // 应用2
		appHooks.put("应用包名", new HookInfo[] {
			new HookInfo("类名", "方法名", 0),
			new HookInfo("类名", "方法名", "1"),
			new HookInfo("类名", "方法名", true)
		});

        return appHooks;
    }
}
