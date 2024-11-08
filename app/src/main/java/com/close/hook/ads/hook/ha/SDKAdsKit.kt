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

        handlePangolinSDK()
        handlePangolinInit()
        handleAnyThinkSDK()
        blockFirebaseWithString()
        blockFirebaseWithString2()
        blockAdsWithBaseBundle()
        blockAdsWithString()
        blockAdsWithPackageName()

        DexKitUtil.releaseBridge()
    }

    fun handlePangolinSDK() {
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

        val foundMethodsForString = DexKitUtil.getCachedOrFindMethods(cacheKeyForString) {
            DexKitUtil.getBridge().findMethod {
                matcher {
                    returnType = "boolean"
                    usingStrings = listOf(initializeMessage)
                }
            }?.toList()
        }

        foundMethodsForString?.let { hookMethods(it, DexKitUtil.context.classLoader) }
    }

    fun blockFirebaseWithString() {
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
                val key = param.args[0] as String
                if ("com.google.android.gms.ads.APPLICATION_ID" == key) {
                    val newValue = "ca-app-pub-0000000000000000~0000000000"
                    param.result = newValue
                }
            }
        )
    }

    fun blockAdsWithString() {
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
                XposedBridge.log("Error hooking method: ${methodData.methodName}")
            }
        }
    }
}
