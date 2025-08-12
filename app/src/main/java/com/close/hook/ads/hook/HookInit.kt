package com.close.hook.ads.hook

import android.content.Context
import android.util.Log
import com.close.hook.ads.hook.gc.DisableClipboard
import com.close.hook.ads.hook.gc.DisableFlagSecure
import com.close.hook.ads.hook.gc.DisableShakeAd
import com.close.hook.ads.hook.gc.HideEnvi
import com.close.hook.ads.hook.gc.network.HideVPNStatus
import com.close.hook.ads.hook.gc.network.RequestHook
import com.close.hook.ads.hook.ha.AppAds
import com.close.hook.ads.hook.ha.CustomHookAds
import com.close.hook.ads.hook.ha.AutoHookAds
import com.close.hook.ads.hook.ha.SDKAdsKit
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.hook.util.ContextUtil
import com.close.hook.ads.hook.util.DexDumpUtil
import com.close.hook.ads.hook.util.HookUtil
import com.close.hook.ads.util.AppUtils
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookInit : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "com.close.hook.ads"
        private const val ENABLE_DEX_DUMP = false
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        ContextUtil.setupContextHooks()
        HookPrefs.initXPrefs()
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.appInfo == null || !lpparam.isFirstApplication) return

        if (lpparam.packageName == TAG) {
            activateModule(lpparam.classLoader)
            return
        }

        try {
            ContextUtil.addOnApplicationContextInitializedCallback {
                val ctx = ContextUtil.applicationContext!!
                val manager = SettingsManager(ctx.packageName, HookPrefs.getXpInstance())

                setupAppHooks(ctx, manager)
                applySettings(manager)
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG | handleLoadPackage error: ${Log.getStackTraceString(e)}")
        }
    }

    private fun activateModule(classLoader: ClassLoader) {
        HookUtil.hookSingleMethod(
            classLoader,
            "com.close.hook.ads.ui.activity.MainActivity",
            "isModuleActivated",
            true
        )
    }

    private fun setupAppHooks(context: Context, manager: SettingsManager) {
        try {
            val classLoader = context.classLoader
            val packageName = context.packageName
            val appName = AppUtils.getAppName(context, packageName)
            val hookPrefs = manager.prefsHelper

            if (ENABLE_DEX_DUMP) {
                DexDumpUtil.dumpDexFilesByPackageName(packageName)
            }

            if (AppUtils.isMainProcess(context)) {
                if (manager.isHookTipEnabled) {
                    AppUtils.showHookTip(context, packageName)
                }
                XposedBridge.log("$TAG | App: $appName Package: $packageName")
            }

            applyCustomHooks(context, classLoader, hookPrefs, packageName)

            AppAds.progress(classLoader, packageName)

        } catch (e: Throwable) {
            XposedBridge.log("$TAG | setupAppHooks error: ${Log.getStackTraceString(e)}")
        }
    }

    private fun applyCustomHooks(context: Context, classLoader: ClassLoader, hookPrefs: HookPrefs, packageName: String) {
        CustomHookAds.hookCustomAds(classLoader, hookPrefs.getCustomHookConfigs(null), true)

        val isOverallHookEnabledForPackage = hookPrefs.getOverallHookEnabled(packageName)
        CustomHookAds.hookCustomAds(classLoader, hookPrefs.getCustomHookConfigs(packageName), isOverallHookEnabledForPackage)
        
        if (isOverallHookEnabledForPackage) {
            AutoHookAds.registerAutoDetectReceiver(context)
            AutoHookAds.findAndCacheSdkMethods(packageName)
        }
    }

    private fun applySettings(manager: SettingsManager) {
        if (manager.isHideVPNStatusEnabled) {
            HideVPNStatus.proxy()
        }
        if (manager.isRequestHookEnabled) {
            RequestHook.init()
        }
        if (manager.isDisableClipboard) {
            DisableClipboard.handle()
        }
        if (manager.isDisableFlagSecureEnabled) {
            DisableFlagSecure.process()
        }
        if (manager.isHideEnivEnabled) {
            HideEnvi.handle()
        }
        if (manager.isHandlePlatformAdEnabled) {
            SDKAdsKit.hookAds()
        }
        if (manager.isDisableShakeAdEnabled) {
            DisableShakeAd.handle()
        }
    }
}
