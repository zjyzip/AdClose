package com.close.hook.ads.hook.ha;

import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.hook.util.HookUtil.HookInfo;

import androidx.collection.ArrayMap;

public class AppAds {

    public static void progress(ClassLoader classLoader, String packageName) {
        // 获取应用的 Hook 配置
        ArrayMap<String, HookInfo[]> appHooks = getAppHooks();

        // 如果存在当前应用的 Hook 信息，进行 Hook 操作
        if (appHooks.containsKey(packageName)) {
            for (HookInfo hookInfo : appHooks.get(packageName)) {
                HookUtil.hookMethods(classLoader, hookInfo.className, hookInfo.methodNames, hookInfo.returnValue);
            }
        }

        // 对特定应用包名进行额外处理
        if (packageName.equals("com.weico.international")) {
            WeiboIE.handle(classLoader);
        }
    }

    // 初始化每个应用的 Hook 信息
    private static ArrayMap<String, HookInfo[]> getAppHooks() {
        ArrayMap<String, HookInfo[]> appHooks = new ArrayMap<>();

        // 示例：为特定应用配置 Hook 信息
        appHooks.put("应用包名", new HookInfo[] {
            // Hook 类的某方法，返回固定值0
            new HookInfo("类名", "方法名", 0),
            // Hook 类的某方法，使其不执行任何操作
            new HookInfo("类名", "方法名", null),
            // Hook 类的某方法，返回 false
            new HookInfo("类名", "方法名", false),
            // Hook 类的多个方法，使其不执行任何操作
            new HookInfo("类名", new String[] { "方法1", "方法2" }, null) 
        });

        // 为另一个应用配置 Hook 信息
        appHooks.put("另一个应用包名", new HookInfo[] {
            // 示例配置
            new HookInfo("类名", "方法名", 0),
            new HookInfo("类名", "方法名", "0"),
            new HookInfo("类名", "方法名", false)
        });

        return appHooks;
    }
}
