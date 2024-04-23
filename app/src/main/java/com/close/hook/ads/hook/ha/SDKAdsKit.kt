package com.close.hook.ads.hook.ha

import android.os.BaseBundle
import java.lang.reflect.Modifier
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import com.close.hook.ads.hook.util.DexKitUtil
import com.close.hook.ads.hook.util.HookUtil.findAndHookMethod
import com.close.hook.ads.hook.util.HookUtil.hookMethod
import org.luckypray.dexkit.result.MethodData
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement

object SDKAdsKit {

    fun blockAds() {
        DexKitUtil.initializeDexKitBridge()

        val packageName = DexKitUtil.context.packageName
        val adPackages = listOf(
            "com.applovin",
            "com.facebook.ads",
            "com.fyber.inneractive.sdk",
            "com.google.android.gms.ads",
            "com.mbridge.msdk",
            "com.inmobi.ads",
            "com.miniclip.ads",
            "com.smaato.sdk",
            "com.tp.adx",
            "com.tradplus.ads",
            "com.unity3d.services",
            "com.unity3d.ads",
            "com.vungle.warren"
        )

        val cacheKeyForAds = "$packageName:blockAds"
        val foundMethodsForAds = DexKitUtil.getCachedOrFindMethods(cacheKeyForAds) {
            DexKitUtil.getBridge().findMethod {
                searchPackages(adPackages)
                matcher {
                    modifiers = Modifier.PUBLIC
                    returnType(Void.TYPE)
                }
            }?.filter { isValidAdMethod(it) }?.toList()
        }

        foundMethodsForAds?.let { hookMethods(it, DexKitUtil.context.classLoader) }

        handlePangolinSDK()

        blockFirebaseWithString()
        blockFirebaseWithString2()

        blockAdsWithBaseBundle()

        blockAdsWithString()

        DexKitUtil.releaseBridge()
    }

    fun handlePangolinSDK() {
        val packageName = DexKitUtil.context.packageName
        val initializeMessage = "tt_sdk_settings_other"
        val cacheKeyForString = "$packageName:handlePangolinSDK"

        val foundMethodsForString = DexKitUtil.getCachedOrFindMethods(cacheKeyForString) {
            DexKitUtil.getBridge().findMethod {
                matcher {
                    usingStrings = listOf(initializeMessage)
                }
            }?.toList()
        }

        foundMethodsForString?.let { hookMethods(it, DexKitUtil.context.classLoader) }
    }

    fun blockFirebaseWithString() {
        val packageName = DexKitUtil.context.packageName
        val initializeMessage = "Device unlocked: initializing all Firebase APIs for app "
        val cacheKeyForString = "$packageName:blockFirebaseWithString"

        val foundMethodsForString = DexKitUtil.getCachedOrFindMethods(cacheKeyForString) {
            DexKitUtil.getBridge().findMethod {
                matcher {
                    usingStrings = listOf(initializeMessage)
                }
            }?.toList()
        }

        foundMethodsForString?.let { hookMethods(it, DexKitUtil.context.classLoader) }
    }

    fun blockFirebaseWithString2() {
        val packageName = DexKitUtil.context.packageName
        val initializeMessage = "[DEFAULT]"
        val cacheKeyForString = "$packageName:blockFirebaseWithString2"

        val foundMethodsForString = DexKitUtil.getCachedOrFindMethods(cacheKeyForString) {
            DexKitUtil.getBridge().findMethod {
                matcher {
                    usingStrings = listOf(initializeMessage)
                    paramTypes("android.content.Context")
                }
            }?.toList()
        }

        foundMethodsForString?.forEach { methodData ->
            try {
                val method = methodData.getMethodInstance(DexKitUtil.context.classLoader)
                hookMethod(method, "after", { param ->
                    param.result = null
                })
            } catch (e: Throwable) {
            }
        }
    }

    fun blockAdsWithBaseBundle() {
        findAndHookMethod(
            BaseBundle::class.java,
            "get",
            "after",
            { param ->
                val key = param.args[0] as String
                if ("com.google.android.gms.ads.APPLICATION_ID" == key) {
                    val newValue = "ca-app-pub-0000000000000000~0000000000"
                    param.result = newValue
                }
            },
            String::class.java
        )
    }

    fun blockAdsWithString() {
        val packageName = DexKitUtil.context.packageName
        val warningMessage = "Flags.initialize() was not called!"
        val cacheKeyForString = "$packageName:blockAdsWithString"

        val foundMethodsForString = DexKitUtil.getCachedOrFindMethods(cacheKeyForString) {
            DexKitUtil.getBridge().findMethod {
                matcher {
                    usingStrings = listOf(warningMessage)
                }
            }?.toList()
        }

        foundMethodsForString?.forEach { methodData ->
            try {
                val method = methodData.getMethodInstance(DexKitUtil.context.classLoader)
                hookMethod(method, "after", { param ->
                    param.result = true
                })
            } catch (e: Throwable) {
            }
        }
    }

    private fun isValidAdMethod(methodData: MethodData): Boolean {
        return !Modifier.isAbstract(methodData.modifiers) && 
               methodData.methodName in listOf("loadAd", "loadAds", "load", "show", "fetchAd")
    }

    private fun hookMethods(methods: List<MethodData>, classLoader: ClassLoader) {
        methods.forEach { methodData ->
            try {
                val method = methodData.getMethodInstance(classLoader)
                XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING)
  //            XposedBridge.log("hook $methodData")
            } catch (e: NoClassDefFoundError) {
            }
        }
    }
}
