package com.close.hook.ads.hook.ha;

import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.hook.util.HookUtil.HookInfo;

public class SDKAds {

    private static final HookInfo[] HOOK_INFOS = {
        new HookInfo("com.example.ClassName", "methodName", 0),
        new HookInfo("com.example.ClassName", "methodName", null),
        new HookInfo("com.example.ClassName", "methodName", false),
        new HookInfo("com.example.ClassName", new String[]{"method1", "method2"}, null)
    };

    public static void hookAds(ClassLoader classLoader) {
        for (HookInfo info : HOOK_INFOS) {
            HookUtil.hookMultipleMethods(classLoader, info.className, info.methodNames, info.returnValue);
        }
    }
}