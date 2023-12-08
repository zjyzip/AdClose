package com.close.hook.ads.hook.ha;

import com.close.hook.ads.hook.util.HookUtil.HookInfo;
import com.close.hook.ads.hook.util.HookUtil;
import de.robv.android.xposed.*;

public class SDKHooks {

	private static final HookInfo[] HOOK_INFOS = {

        // Cloud Injection
        new HookInfo("com.cloudinject.feature.App", "̗̗", null),

        //岳鹰全景监控
        new HookInfo("com.uc.crashsdk.export.CrashApi", "createInstanceEx", null),

/*
        // 应用解锁 - com.ming.app.clickplus
        new HookInfo("com.ming.app.clickplus.util.UserInfoStorgeKt", "getUserType", 3),
        new HookInfo("com.ming.app.clickplus.util.UserInfoStorgeKt", "getExpireTime", "2099-12-31"),
        new HookInfo("com.ming.app.clickplus.util.UserInfoStorgeKt", "getUserIsExpired", false),
*/

        // ByteDance (Pangolin) Ads
        new HookInfo("com.bytedance.sdk.openadsdk.TTAdSdk", "init", null),

        new HookInfo("com.bytedance.sdk.openadsdk.TTAppContextHolder", "setContext", null),

        new HookInfo("com.bytedance.sdk.openadsdk.TTAdConfig", new String[] { "getAppId", "getSdkInfo" }, null),

        // ByteDance (Pangolin) GroMore
        new HookInfo("com.bytedance.sdk.openadsdk.AdSlot$Builder", "build", null),

        new HookInfo("com.bytedance.msdk.api.v2.GMAdConfig", "getAppId", null),

        new HookInfo("com.bytedance.msdk.api.v2.GMMediationAdSdk", new String[] { "getAppId", "preload", "initialize" }, null),

        // Kwai Ads
        new HookInfo("com.kwad.sdk.KsAdSDKImpl", "init", null),

        // Tencent Ads
        new HookInfo("com.qq.e.comm.managers.status.SDKStatus", "getPluginVersion", 0),
        new HookInfo("com.qq.e.comm.managers.status.SDKStatus", "getSDKVersion", null),

        // Baidu Ads
        new HookInfo("com.baidu.mobads.sdk.api.BDAdConfig", "init", null),

        // Sigmob Ads
        new HookInfo("com.sigmob.sdk.Sigmob", "init", null),

        // ADSuyiSdk Ads
        new HookInfo("cn.admobiletop.adsuyi.ADSuyiSdk", "init", null),

        // AdScope
        new HookInfo("com.beizi.fusion.BeiZis", new String[] { "init", "asyncInit" }, null),

        // BJXingu Ads
        new HookInfo("com.link.sdk.client.AdRequest", "init", null),

        // XinwuPaijin Ads
        new HookInfo("com.xwuad.sdk.client.PijSDK", "init", null),

        // Qumeng Ads
        new HookInfo("com.qumeng.advlib.api.AiClkAdManager", "init", null),

        // Huawei Ads
        new HookInfo("com.huawei.hms.ads.HwAds", "init", null),

        new HookInfo("com.huawei.hms.hatool.HmsHiAnalyticsUtils", "init", null),

        // Mbridge Ads
        new HookInfo("com.mbridge.msdk.foundation.entity.CampaignUnit", "getAdHtml", null),
        new HookInfo("com.mbridge.msdk.out.MBridgeSDKFactory", "getMBridgeSDK", null),

        // Mintegral Ads
        new HookInfo("com.windmill.sdk.WindMillAd", new String[] { "getAppId", "getAdConfig" }, null),

        // Tanx Ads
        new HookInfo("com.alimm.tanx.ui.TanxSdk", "init", null),

        new HookInfo("com.alimm.tanx.core.TanxCoreSdk", "init", null),

        new HookInfo("com.alimm.tanx.core.TanxCoreManager", "init", null),

        // Umeng SDK
        new HookInfo("com.umeng.commonsdk.UMConfigure", new String[] { "init", "preInit" }, null),

        new HookInfo("com.umeng.commonsdk.utils.UMUtils", "getAppkey", null),

        new HookInfo("com.umeng.umzid.ZIDManager", "init", null),

        new HookInfo("com.umeng.umcrash.UMCrash", new String[] { "init", "initConfig" }, null),

        new HookInfo("com.umeng.message.PushAgent", new String[] { "enable", "register", "onAppStart" }, null),

        // Xiaomi Ads
        new HookInfo("com.miui.zeus.mimo.sdk.MimoSdk", new String[] { "init", "setDebugOn", "setStagingOn" }, null),


        // XiaoChuang-ZuiYou
        new HookInfo("cn.xiaochuankeji.hermes.core.Hermes", "init", null),

        new HookInfo("cn.xiaochuankeji.xcad.sdk.XcADSdk", "init", null),

        new HookInfo("cn.xiaochuankeji.hermes.core.model.ADDSPConfig", "getAppKey", null),

        new HookInfo("cn.xiaochuankeji.hermes.core.workflow.init.InitUtil", "getAppID", null),

        new HookInfo("cn.xiaochuankeji.hermes.core.provider.ADSDKInitParam", "getConfig", null),

        new HookInfo("cn.xiaochuankeji.hermes.core.api.entity.ADConfigResponseDataKt", "getAppIDallRegisteredSDKConfigs", null),

        // Tencent SDK
        new HookInfo("com.tencent.bugly.Bugly", "init", null),

        new HookInfo("com.tencent.bugly.CrashModule", "init", null),

        new HookInfo("com.tencent.klevin.KlevinManager", "init", null),

        new HookInfo("com.tencent.bugly.crashreport.CrashReport", "initCrashReport", null),


		//阿里百川广告
		new HookInfo("com.alibaba.baichuan.trade.biz.AlibcTradeBiz","init",null),
		new HookInfo("com.alibaba.baichuan.android.trade.AlibcTradeSDK","asyncInit",null),


		// Vungle Sdk
		new HookInfo("com.vungle.warren.Vungle","init",null),
		new HookInfo("com.uc.crashsdk.export.CrashApi","updateCustomInfo",null),
		
		// Applovin Sdk
        new HookInfo("com.applovin.sdk.AppLovinSdk", new String[] { "initializeSdk", "reinitializeAll" }, null),


        // Unity3d Ads
        new HookInfo("com.unity3d.services.UnityServices", "initialize", null),

        new HookInfo("com.google.ads.mediation.unity.UnityInitializer", "initializeUnityAds", null),

		// Unity Ads
		new HookInfo("com.unity3d.ads.UnityAds","initialize",null),
		new HookInfo("com.unity3d.services.core.api.Sdk","reinitialize",null),
		new HookInfo("com.unity3d.services.core.configuration.InitializeThread","initialize",null),


        // Google Ads
		new HookInfo("com.google.android.gms.ads.MobileAds","initialize",null),

		new HookInfo("com.google.android.gms.ads.AdRequest","getContentUrl",null),

        new HookInfo("com.google.android.gms.ads.AdRequest$Builder", new String[] { "setRequestAgent", "setContentUrl", "setRequestAgent", "setAdString", "setAdInfo" }, null),


		// FaceBook Ads
		new HookInfo("com.facebook.ads.AudienceNetworkAds","initialize",null),
		new HookInfo("com.facebook.ads.internal.dynamicloading.DynamicLoaderFactory","initialize",null)

	};

	public static void hookAds(ClassLoader classLoader) {
		for (HookInfo info : HOOK_INFOS) {
			HookUtil.hookMethods(classLoader, info.className, info.methodNames, info.returnValue);
		}
	}
}
