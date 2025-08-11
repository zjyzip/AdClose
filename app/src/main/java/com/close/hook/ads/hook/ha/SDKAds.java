package com.close.hook.ads.hook.ha;

import android.app.AndroidAppHelper;
import android.content.Context;
import com.close.hook.ads.hook.util.ContextUtil;
import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.hook.util.HookUtil.HookInfo;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Method;
import java.util.Arrays;

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
                try {
                    Class<?> clazz = cl.loadClass(info.className);

                    for (String methodName : info.methodNames) {

                        for (Method method : clazz.getDeclaredMethods()) {
                            if (method.getName().equals(methodName)) {
                                XposedBridge.log("Found method: " + method);

                                if (hasContextReturnType(method)) {
                                    XposedBridge.log("Method is Context-related. Hooking with FakeContextWrapper.");

                                    HookUtil.hookMethod(method, "after", param -> {
                                        XposedBridge.log("Successfully replaced Context for " + method);
                                        param.setResult(new FakeContextWrapper(ContextUtil.INSTANCE.applicationContext));
                                    });
                                } else {
                                    XposedBridge.log("Method is not Context-related. Hooking with specified return value: " + info.returnValue);
                                    HookUtil.hookMultipleMethods(cl, info.className, info.methodNames, info.returnValue);
                                }
                            }
                        }
                    }
                } catch (ClassNotFoundException e) {
                } catch (Throwable t) {
                    XposedBridge.log("Unexpected error during hook for " + info.className + ": " + t.getMessage());
                }
            }
        });
    }

    private static boolean hasContextReturnType(Method method) {
        return Context.class.isAssignableFrom(method.getReturnType());
    }
}
