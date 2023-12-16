package com.close.hook.ads.hook.ha;

import android.app.AndroidAppHelper;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class WeiboIE {

    public static void KillAD(ClassLoader classLoader) {
        blockAds(classLoader);
        enableVideoDownload(classLoader);
        modifySettings(classLoader);
        blockQueryUveAdRequest(classLoader);
    }

    private static void blockAds(ClassLoader classLoader) {
        hookMethodForBlockingAds(classLoader, "com.weico.international.model.sina.Status", "isWeiboUVEAd", "com.weico.international.utility.KotlinExtendKt", false);
        hookMethodForBlockingAds(classLoader, "com.weico.international.model.sina.PageInfo", "findUVEAd", "com.weico.international.utility.KotlinUtilKt", null);
        hookMethodForLogoActivity(classLoader);
    }

    private static void hookMethodForBlockingAds(ClassLoader classLoader, String className, String methodName, String hookClass, Object result) {
        Class<?> clazz = XposedHelpers.findClass(className, classLoader);
        XposedHelpers.findAndHookMethod(hookClass, classLoader, methodName, clazz, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.setResult(result);
            }
        });
    }

    private static void hookMethodForLogoActivity(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("com.weico.international.activity.LogoActivity", classLoader, "doWhatNext", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param != null && "AD".equals(param.getResult())) {
                    param.setResult("main");
                }
            }
        });
    }

    private static void enableVideoDownload(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("com.weico.international.data.VideoModalOTO", classLoader, "getDownloadAble", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.setResult(true);
            }
        });
    }

    private static void modifySettings(ClassLoader classLoader) {
    String settingClass = "com.weico.international.activity.v4.Setting";

    XposedHelpers.findAndHookMethod(settingClass, classLoader, "loadBoolean", String.class, new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            String key = (String) param.args[0];
            if ("BOOL_UVE_FEED_AD".equals(key)) {
                param.setResult(false);
            } else if (key.startsWith("BOOL_AD_ACTIVITY_BLOCK_")) {
                param.setResult(true);
            }
        }
    });

    XposedHelpers.findAndHookMethod(settingClass, classLoader, "loadInt", String.class, new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            String key = (String) param.args[0];
            if ("ad_interval".equals(key)) {
                param.setResult(Integer.MAX_VALUE);
            } else if ("display_ad".equals(key)) {
                param.setResult(0);
            }
        }
    });

    XposedHelpers.findAndHookMethod(settingClass, classLoader, "loadStringSet", String.class, new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            String key = (String) param.args[0];
            if ("CYT_DAYS".equals(key)) {
                param.setResult(new HashSet<String>());
            }
        }
    });

    XposedHelpers.findAndHookMethod(settingClass, classLoader, "loadString", String.class, new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            String key = (String) param.args[0];
            if ("video_ad".equals(key)) {
                param.setResult("");
            }
        }
    });
}

    private static void blockQueryUveAdRequest(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("com.weico.international.api.RxApiKt", classLoader, "queryUveAdRequest", Map.class, String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Map<String, Object> map = (Map<String, Object>) param.args[0];
                map.remove("ip");
                map.remove("uid");
            }
        });
    }
}
