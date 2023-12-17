package com.close.hook.ads.hook.ha;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import android.os.Bundle;
import android.widget.Toast;
import android.app.Activity;
import android.app.AndroidAppHelper;

import de.robv.android.xposed.*;

public class WeiboIE {

    public static void handle(ClassLoader classLoader) {
        blockAds(classLoader);
        modifySettings(classLoader);
        blockQueryUveAdRequest(classLoader);

    }

    private static void blockAds(ClassLoader classLoader) {

        Class<?> StatusClass = XposedHelpers.findClass("com.weico.international.model.sina.Status", classLoader);
        XposedHelpers.findAndHookMethod("com.weico.international.utility.KotlinExtendKt", classLoader, "isWeiboUVEAd", StatusClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(false);
                }
            });

        Class<?> PageInfo = XposedHelpers.findClass("com.weico.international.model.sina.PageInfo", classLoader);
        XposedHelpers.findAndHookMethod("com.weico.international.utility.KotlinUtilKt", classLoader, "findUVEAd", PageInfo, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(null);
                }
            });

        XposedHelpers.findAndHookMethod("com.weico.international.activity.LogoActivity", classLoader, "doWhatNext", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param != null) {
                        if ("AD".equals(param.getResult())) {
                            param.setResult("main");
                        }
                    }
                }
            });

        try {
            XposedHelpers.findAndHookMethod("com.weico.international.manager.ProcessMonitor", classLoader, "displayAd", Long.class, Activity.class, Boolean.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return true;
                    }
                });
        } catch (NoSuchMethodError e) {
        }
    }

    private static void modifySettings(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("com.weico.international.activity.v4.Setting", classLoader, "loadBoolean", String.class, new XC_MethodHook() {
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

        XposedHelpers.findAndHookMethod("com.weico.international.activity.v4.Setting", classLoader, "loadInt", String.class, new XC_MethodHook() {
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

        XposedHelpers.findAndHookMethod("com.weico.international.activity.v4.Setting", classLoader, "loadStringSet", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String key = (String) param.args[0];
                    if ("CYT_DAYS".equals(key)) {
                        param.setResult(new HashSet<String>());
                    }
                }
            });

        XposedHelpers.findAndHookMethod("com.weico.international.activity.v4.Setting", classLoader, "loadString", String.class, new XC_MethodHook() {
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
