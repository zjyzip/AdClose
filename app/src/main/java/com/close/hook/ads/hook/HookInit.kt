package com.close.hook.ads.hook

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.close.hook.ads.hook.gc.DisableClipboard
import com.close.hook.ads.hook.gc.DisableFlagSecure
import com.close.hook.ads.hook.gc.DisableShakeAd
import com.close.hook.ads.hook.gc.HideEnvi
import com.close.hook.ads.hook.gc.network.HideVPNStatus
import com.close.hook.ads.hook.gc.network.RequestHook
import com.close.hook.ads.hook.ha.AppAds
import com.close.hook.ads.hook.ha.SDKAds
import com.close.hook.ads.hook.ha.SDKAdsKit
import com.close.hook.ads.hook.preference.PreferencesHelper
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

        private var settingsManager: SettingsManager? = null
        private var applicationContext: Context? = null

        init {
            ContextUtil.addOnApplicationContextInitializedCallback {
                applicationContext = ContextUtil.applicationContext
                setupAppHooks()
            }
        }

        private fun setupAppHooks() {
            try {
                val context = applicationContext ?: return
                val classLoader = context.classLoader
                val packageName = context.packageName
                val appName = AppUtils.getAppName(context, packageName)

                if (TAG == packageName) {
                    activateModule(classLoader)
                    return
                }

                if (ENABLE_DEX_DUMP) {
                    DexDumpUtil.dumpDexFilesByPackageName(packageName)
                }

                settingsManager?.let { manager ->
                    if (AppUtils.isMainProcess(context) && manager.isHookTipEnabled) {
                        AppUtils.showHookTip(context, packageName)
                    }

                    if (manager.isHandlePlatformAdEnabled) {
                        SDKAds.hookAds(classLoader)
                    }
                }

                XposedBridge.log("$TAG | App: $appName Package: $packageName")

                AppAds.progress(classLoader, packageName)

            } catch (e: Throwable) {
                XposedBridge.log("$TAG | setupAppHooks error: ${Log.getStackTraceString(e)}")
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
                SDKAdsKit.blockAds()
            }
            if (manager.isDisableShakeAdEnabled) {
                DisableShakeAd.handle()
            }
        }
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        ContextUtil.initialize {
            XposedBridge.log("$TAG | ContextUtil initialized.")
        }
    }

    @SuppressLint("SuspiciousIndentation")
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (shouldIgnorePackage(lpparam)) return

        try {
            val prefsHelper = PreferencesHelper()
            val manager = SettingsManager(prefsHelper, lpparam.packageName)
            settingsManager = manager
            applySettings(manager)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG | handleLoadPackage error: ${Log.getStackTraceString(e)}")
        }
    }

    private fun shouldIgnorePackage(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        return lpparam.appInfo == null || !lpparam.isFirstApplication
    }
}
