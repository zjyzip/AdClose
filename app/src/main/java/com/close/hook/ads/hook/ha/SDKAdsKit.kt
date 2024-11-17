package com.close.hook.ads.hook.ha

import android.os.BaseBundle
import java.lang.reflect.Modifier
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import com.close.hook.ads.hook.util.DexKitUtil
import com.close.hook.ads.hook.util.HookUtil.findAndHookMethod
import com.close.hook.ads.hook.util.HookUtil.hookAllMethods
import com.close.hook.ads.hook.util.HookUtil.hookMethod
import org.luckypray.dexkit.result.MethodData

object SDKAdsKit {

    private val packageName: String by lazy { DexKitUtil.context.packageName }

    fun blockAds() {
        DexKitUtil.initializeDexKitBridge()

        try {
            handlePangolinSDK()
            handlePangolinInit()
            handleAnyThinkSDK()
            blockFirebaseWithString()
            blockFirebaseWithString2()
            blockAdsWithBaseBundle()
            blockAdsWithString()
            blockAdsWithPackageName()
        } catch (e: Throwable) {
            XposedBridge.log("Error in blockAds: ${e.message}")
        } finally {
            DexKitUtil.releaseBridge()
        }
    }

    fun handlePangolinSDK() {
        val initializeMessage = "tt_sdk_settings_other"
        val cacheKeyForString = "$packageName:handlePangolinSDK"

        DexKitUtil.getCachedOrFindMethods(cacheKeyForString) {
            DexKitUtil.getBridge().findMethod {
                matcher {
                    usingStrings = listOf(initializeMessage)
                }
            }
        }?.let { methods ->
            methods.forEach { methodData ->
                try {
                    val method = methodData.getMethodInstance(DexKitUtil.context.classLoader)
                    XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING)
                    XposedBridge.log("Hooked method: ${methodData}")
                } catch (e: Throwable) {
                    XposedBridge.log("Error hooking method: ${methodData.methodName}")
                }
            }
        }
    }

    fun handlePangolinInit() {
        try {
            val ttAdSdkClass = Class.forName("com.bytedance.sdk.openadsdk.TTAdSdk", false, DexKitUtil.context.classLoader)

            val methods = ttAdSdkClass.declaredMethods
            val initMethodExists = methods.any { it.name == "init" }

            if (!initMethodExists) {
                return
            }

            hookAllMethods(
                "com.bytedance.sdk.openadsdk.TTAdSdk",
                "init",
                "after",
                { param ->
                    val method = param.method as java.lang.reflect.Method
                    val returnType = method.returnType

                    when (returnType) {
                        Void.TYPE -> param.result = null
                        java.lang.Boolean.TYPE -> param.result = false
                        else -> param.result = null
                    }
                },
                DexKitUtil.context.classLoader
            )
        } catch (e: ClassNotFoundException) {
        }
    }

    fun handleAnyThinkSDK() {
        val initializeMessage = "anythink_sdk"
        val cacheKeyForString = "$packageName:handleAnyThinkSDK"

        DexKitUtil.getCachedOrFindMethods(cacheKeyForString) {
            DexKitUtil.getBridge().findMethod {
                matcher {
                    returnType = "boolean"
                    usingStrings = listOf(initializeMessage)
                }
            }
        }?.let { methods ->
            methods.forEach { methodData ->
                try {
                    val method = methodData.getMethodInstance(DexKitUtil.context.classLoader)
                    XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING)
                    XposedBridge.log("Hooked method: ${methodData}")
                } catch (e: Throwable) {
                    XposedBridge.log("Error hooking method: ${methodData.methodName}")
                }
            }
        }
    }

    fun blockFirebaseWithString() {
        val initializeMessage = "Device unlocked: initializing all Firebase APIs for app "
        val cacheKeyForString = "$packageName:blockFirebaseWithString"

        DexKitUtil.getCachedOrFindMethods(cacheKeyForString) {
            DexKitUtil.getBridge().findMethod {
                matcher {
                    usingStrings = listOf(initializeMessage)
                }
            }
        }?.let { methods ->
            methods.forEach { methodData ->
                try {
                    val method = methodData.getMethodInstance(DexKitUtil.context.classLoader)
                    XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING)
                    XposedBridge.log("Hooked method: ${methodData}")
                } catch (e: Throwable) {
                    XposedBridge.log("Error hooking method: ${methodData.methodName}")
                }
            }
        }
    }

    fun blockFirebaseWithString2() {
        val initializeMessage = "[DEFAULT]"
        val cacheKeyForString = "$packageName:blockFirebaseWithString2"

        DexKitUtil.getCachedOrFindMethods(cacheKeyForString) {
            DexKitUtil.getBridge().findMethod {
                matcher {
                    usingStrings = listOf(initializeMessage)
                    paramTypes("android.content.Context")
                }
            }
        }?.forEach { methodData ->
            try {
                val method = methodData.getMethodInstance(DexKitUtil.context.classLoader)
                XposedBridge.log("Hooked method: ${methodData}")
                hookMethod(method, "after", { param ->
                    param.result = null
                })
            } catch (e: Throwable) {
                XposedBridge.log("Error hooking method: ${methodData.methodName}")
            }
        }
    }

    fun blockAdsWithBaseBundle() {
        findAndHookMethod(
            BaseBundle::class.java,
            "get",
            arrayOf(String::class.java),
            "after",
            { param ->
                val key = param.args[0] as? String
                if ("com.google.android.gms.ads.APPLICATION_ID" == key) {
                    param.result = "ca-app-pub-0000000000000000~0000000000"
                }
            }
        )
    }

    fun blockAdsWithString() {
        val warningMessage = "Flags.initialize() was not called!"
        val cacheKeyForString = "$packageName:blockAdsWithString"

        DexKitUtil.getCachedOrFindMethods(cacheKeyForString) {
            DexKitUtil.getBridge().findMethod {
                matcher { usingStrings = listOf(warningMessage) }
            }
        }?.forEach { methodData ->
            try {
                val method = methodData.getMethodInstance(DexKitUtil.context.classLoader)
                XposedBridge.log("Hooked method: ${methodData}")
                hookMethod(method, "after", { param ->
                    param.result = true
                })
            } catch (e: Throwable) {
                XposedBridge.log("Error hooking method: ${methodData.methodName}")
            }
        }
    }

    fun blockAdsWithPackageName() {
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

        val cacheKeyForAds = "$packageName:blockAdsWithPackageName"
        DexKitUtil.getCachedOrFindMethods(cacheKeyForAds) {
            DexKitUtil.getBridge().findMethod {
                searchPackages(adPackages)
                matcher {
                    modifiers = Modifier.PUBLIC
                    returnType(Void.TYPE)
                }
            }?.filter(::isValidAdMethod)
        }?.forEach { methodData ->
            try {
                val method = methodData.getMethodInstance(DexKitUtil.context.classLoader)
                XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING)
                XposedBridge.log("Hooked method: ${methodData}")
            } catch (e: Throwable) {
                XposedBridge.log("Error hooking method: ${methodData.methodName}")
            }
        }
    }

    private fun isValidAdMethod(methodData: MethodData): Boolean {
        return !Modifier.isAbstract(methodData.modifiers) &&
               methodData.methodName in listOf("loadAd", "loadAds", "load", "show", "fetchAd")
    }
}
