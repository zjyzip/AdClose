package com.close.hook.ads.hook.ha;

import android.content.Context;
import com.close.hook.ads.hook.util.ContextUtil;
import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.hook.util.HookUtil.HookInfo;

public class SDKAds {

    private static final HookInfo[] HOOK_INFOS = {
        new HookInfo("com.example.ClassName", "methodName", 0),
        new HookInfo("com.example.ClassName", "methodName", null),
        new HookInfo("com.example.ClassName", "methodName", false),
        new HookInfo("com.example.ClassName", new String[]{"method1", "method2"}, null)
    };


    public static void hookAds() {
        ContextUtil.INSTANCE.addOnApplicationContextInitializedCallback(() -> {
            ClassLoader cl = ContextUtil.INSTANCE.applicationContext.getClassLoader();
            for (HookInfo info : HOOK_INFOS) {
                HookUtil.hookMultipleMethods(cl, info.className, info.methodNames, info.returnValue);
            }
        });
    }
}