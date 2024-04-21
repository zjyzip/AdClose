package com.close.hook.ads.hook.ha;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.HashSet;
import java.util.Map;
import java.util.function.Consumer;
import com.close.hook.ads.hook.util.HookUtil;

public class WeiboIE {

	public static void handle(ClassLoader classLoader) {
		blockAds(classLoader);
		modifySettings(classLoader);
		blockQueryUveAdRequest(classLoader);
	}

	private static void blockAds(ClassLoader classLoader) {
		HookUtil.hookSingleMethod(classLoader, "com.weico.international.utility.KotlinExtendKt", "isWeiboUVEAd", false);
		HookUtil.hookSingleMethod(classLoader, "com.weico.international.utility.KotlinUtilKt", "findUVEAd", null);
		HookUtil.hookSingleMethod(classLoader, "com.weico.international.activity.LogoActivity", "doWhatNext", "main");
	}

	private static void modifySettings(ClassLoader classLoader) {
		Consumer<XC_MethodHook.MethodHookParam> settingBlocker = param -> {
			String key = (String) param.args[0];
			switch (key) {
			case "BOOL_UVE_FEED_AD_BLOCK":
				param.setResult(true);
				break;
			case "ad_interval":
				param.setResult(Integer.MAX_VALUE);
				break;
			case "display_ad":
				param.setResult(0);
				break;
			case "video_ad":
				param.setResult("");
				break;
			case "CYT_DAYS":
				param.setResult(new HashSet<String>());
				break;
			default:
				if (key.startsWith("BOOL_AD_ACTIVITY_BLOCK_") || key.startsWith("BOOL_UVE_FEED_AD_BLOCK")) {
					param.setResult(true);
				}
				break;
			}
		};

		HookUtil.hookSingleMethod(classLoader, "com.weico.international.activity.v4.Setting", "loadBoolean", settingBlocker);
		HookUtil.hookSingleMethod(classLoader, "com.weico.international.activity.v4.Setting", "loadInt", settingBlocker);
		HookUtil.hookSingleMethod(classLoader, "com.weico.international.activity.v4.Setting", "loadStringSet",
				settingBlocker);
		HookUtil.hookSingleMethod(classLoader, "com.weico.international.activity.v4.Setting", "loadString", settingBlocker);
	}

	private static void blockQueryUveAdRequest(ClassLoader classLoader) {
		HookUtil.hookAllMethods(XposedHelpers.findClass("com.weico.international.api.RxApiKt", classLoader),
				"queryUveAdRequest", "before", param -> {
					Map<String, Object> map = (Map<String, Object>) param.args[0];
					map.remove("ip");
					map.remove("uid");
				});
	}
}
