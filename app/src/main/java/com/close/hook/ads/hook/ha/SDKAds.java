package com.close.hook.ads.hook.ha;

import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.hook.util.HookUtil.HookInfo;

public class SDKAds {

    // HOOK_INFOS数组包含了HookInfo对象的列表，每个对象指定了需要被hook的类名、方法名和hook方法时返回的值。
    // 这些只是用法示例，实际应用中需要根据具体需要和情况来定义。
    private static final HookInfo[] HOOK_INFOS = {
        // 示例：禁止某个SDK的初始化方法执行。
        new HookInfo("com.ap.android.trunk.sdk.core.APSDK", "init", null),

        // 示例：强制另一个SDK的初始化方法返回false，表示初始化失败。
        new HookInfo("cn.xiaochuankeji.hermes.core.workflow.init.InitUtil", "init", false),

        // 示例：修改SDK的插件版本号返回值为0。
        new HookInfo("com.qq.e.comm.managers.status.SDKStatus", "getPluginVersion", 0),

        // 示例：阻止SDK类中的多个关键方法（init和start）执行。
        new HookInfo("com.bytedance.sdk.openadsdk.TTAdSdk", new String[] { "init", "start" }, null)
    };

    public static void hookAds(ClassLoader classLoader) {
        for (HookInfo info : HOOK_INFOS) {
            // 遍历每个 HookInfo 来应用 hook，将方法的执行结果替换为指定的返回值。
            HookUtil.hookMultipleMethods(classLoader, info.className, info.methodNames, info.returnValue);
        }
    }
}