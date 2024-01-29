package com.close.hook.ads.hook.ha;

import com.close.hook.ads.hook.util.HookUtil;
import com.close.hook.ads.hook.util.HookUtil.HookInfo;

public class SDKAds {

	private static final HookInfo[] HOOK_INFOS = {

		new HookInfo("com.ap.android.trunk.sdk.core.APSDK", "init", null),

		new HookInfo("cn.xiaochuankeji.hermes.core.workflow.init.InitUtil", "init", false),

		new HookInfo("com.qq.e.comm.managers.status.SDKStatus", "getPluginVersion", 0),

		new HookInfo("com.bytedance.sdk.openadsdk.TTAdSdk", new String[] { "init", "start" }, null)

	};

	public static void hookAds(ClassLoader classLoader) {
		for (HookInfo info : HOOK_INFOS) {
			HookUtil.hookMethods(classLoader, info.className, info.methodNames, info.returnValue);
		}
	}
}
