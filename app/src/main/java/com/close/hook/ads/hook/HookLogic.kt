package com.close.hook.ads.hook

import android.content.Context
import android.util.Log
import com.close.hook.ads.hook.gc.DisableClipboard
import com.close.hook.ads.hook.gc.DisableFlagSecure
import com.close.hook.ads.hook.gc.DisableShakeAd
import com.close.hook.ads.hook.gc.HideEnvi
import com.close.hook.ads.hook.gc.network.HideVPNStatus
import com.close.hook.ads.hook.gc.network.NativeRequestHook
import com.close.hook.ads.hook.gc.network.RequestHook
import com.close.hook.ads.hook.gc.network.RequestHookHandler
import com.close.hook.ads.hook.ha.AppAds
import com.close.hook.ads.hook.ha.AutoHookAds
import com.close.hook.ads.hook.ha.CustomHookAds
import com.close.hook.ads.hook.ha.SDKAdsKit
import com.close.hook.ads.hook.util.ContextUtil
import com.close.hook.ads.hook.util.DexDumpUtil
import com.close.hook.ads.hook.util.LogProxy
import com.close.hook.ads.manager.SettingsManager
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.util.AppUtils
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object HookLogic {

    var xposedInterface: XposedInterface? = null
    private const val TAG = "com.close.hook.ads"
    private val hookScope = CoroutineScope(Dispatchers.IO)

    fun loadPackage(param: XposedModuleInterface.PackageLoadedParam) {
        if (!param.isFirstPackage || param.packageName == TAG) return

        ContextUtil.addOnApplicationContextInitializedCallback {
            ContextUtil.applicationContext?.let { context ->
                try {
                    val manager = SettingsManager(param.packageName, HookPrefs)
                    setupAppHooks(context, manager)
                    applySettings(context, manager)
                } catch (e: Throwable) {
                    xposedInterface?.log(Log.ERROR, TAG, "Error in package ${param.packageName}: ${Log.getStackTraceString(e)}")
                }
            } ?: xposedInterface?.log(Log.WARN, TAG, "FATAL: Context was null for ${param.packageName}")
        }
    }

    private fun setupAppHooks(context: Context, manager: SettingsManager) {
        val classLoader = context.classLoader
        val packageName = context.packageName

        if (HookPrefs.getBoolean(HookPrefs.KEY_ENABLE_DEX_DUMP, false)) {
            DexDumpUtil.dumpDexFilesByPackageName(packageName)
        }

        if (AppUtils.isMainProcess(context)) {
            if (manager.isHookTipEnabled) {
                AppUtils.showHookTip(context, packageName)
            }
            val appName = AppUtils.getAppName(context, packageName)
            xposedInterface?.log(Log.INFO, TAG, "Hooking App: $appName ($packageName)")
        }

        SDKAdsKit.hookAds()
        AppAds.progress(classLoader, packageName)

        applyClassLoaderHooks(classLoader, manager.prefsHelper, packageName, context)
    }

    private fun applyClassLoaderHooks(
        classLoader: ClassLoader,
        hookPrefs: HookPrefs,
        packageName: String,
        context: Context
    ) {
        val hookTasks = listOf(
            { hookPrefs.getCustomHookConfigs(null) } to true,
            { hookPrefs.getCustomHookConfigs(packageName) } to hookPrefs.getOverallHookEnabled(packageName)
        )

        hookTasks.forEach { (configProvider, isEnabled) ->
            CustomHookAds.hookCustomAds(classLoader, configProvider(), isEnabled)
        }

        if (hookPrefs.getOverallHookEnabled(packageName)) {
            AutoHookAds.registerAutoDetectReceiver(context)
            hookScope.launch {
                AutoHookAds.findAndCacheSdkMethods(packageName)
            }
            if (hookPrefs.getEnableLogging(packageName)) {
                LogProxy.init(context)
            }
        }
    }

    private fun applySettings(context: Context, manager: SettingsManager) {
        manager.run {
            val needRequestHook = isRequestHookEnabled
            val needNativeHook = isNativeRequestHookEnabled

            if (needRequestHook || needNativeHook) {
                RequestHook.init(context)
                NativeRequestHook.init(needNativeHook) 
            }

            if (needRequestHook) RequestHookHandler.init(context)
            
            if (isHideVPNStatusEnabled) HideVPNStatus.proxy()
            if (isDisableFlagSecureEnabled) DisableFlagSecure.process()
            if (isDisableShakeAdEnabled) DisableShakeAd.handle()
            if (isHideEnivEnabled) HideEnvi.handle()
            if (isDisableClipboard) DisableClipboard.handle()
        }
    }
}
