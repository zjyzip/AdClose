package com.close.hook.ads.hook.ha;

import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.hook.util.HookUtil.HookInfo;

import androidx.collection.ArrayMap;

public class AppAds {

    public static void progress(ClassLoader classLoader, String packageName) {
        ArrayMap<String, HookInfo[]> appHooks = getAppHooks();
        HookInfo[] hooks = appHooks.get(packageName);

        if (hooks != null) {
            for (HookInfo hookInfo : hooks) {
                HookUtil.hookMultipleMethods(classLoader, hookInfo.className, hookInfo.methodNames, hookInfo.returnValue);
            }
        }

        if ("com.weico.international".equals(packageName)) {
            WeiboIE.handle(classLoader);
        }
    }

    private static ArrayMap<String, HookInfo[]> getAppHooks() {
        ArrayMap<String, HookInfo[]> appHooks = new ArrayMap<>();

        appHooks.put("com.example.app", new HookInfo[]{
                new HookInfo("com.example.ClassName", new String[]{"methodName"}, 0),
                new HookInfo("com.example.ClassName", new String[]{"methodName"}, null),
                new HookInfo("com.example.ClassName", new String[]{"methodName"}, false),
                new HookInfo("com.example.ClassName", new String[]{"method1", "method2"}, null)
        });

        appHooks.put("com.example.anotherApp", new HookInfo[]{
                new HookInfo("com.example.anotherClass", new String[]{"methodName"}, 0),
                new HookInfo("com.example.anotherClass", new String[]{"methodName"}, "0"),
                new HookInfo("com.example.anotherClass", new String[]{"methodName"}, false)
        });

        return appHooks;
    }
}
