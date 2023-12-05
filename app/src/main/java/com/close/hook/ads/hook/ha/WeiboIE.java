package com.close.hook.ads.hook.ha;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.net.URLDecoder;

import android.os.Bundle;
import android.widget.Toast;
import android.app.Activity;
import android.app.AndroidAppHelper;

import de.robv.android.xposed.*;

public class WeiboIE {

    public static void KillAD(ClassLoader classLoader) {

        blockAds(classLoader);
        blockWebviewRedirection(classLoader);
        enableVideoDownload(classLoader);
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
        }

    private static void blockWebviewRedirection(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("com.weico.international.activity.WebviewActivity", classLoader, "loadUrl", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String url = (String) param.args[0];
                    String sinaUrl = "://weibo.cn/sinaurl?u=";
                    if (url.contains(sinaUrl)) {
                        url = url.substring(url.indexOf(sinaUrl) + sinaUrl.length());
                        url = URLDecoder.decode(url);
                        param.args[0] = url;
                    } else {
                        Toast.makeText(AndroidAppHelper.currentApplication().getApplicationContext(), "WebviewActivity-" + url, Toast.LENGTH_SHORT).show();
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
