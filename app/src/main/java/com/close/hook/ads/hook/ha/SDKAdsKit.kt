package com.close.hook.ads.hook.ha

import android.os.BaseBundle
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import com.close.hook.ads.hook.util.DexKitUtil
import com.close.hook.ads.hook.util.HookUtil.findAndHookMethod
import com.close.hook.ads.hook.util.HookUtil.hookAllMethods
import com.close.hook.ads.hook.util.HookUtil.hookMethod
import org.luckypray.dexkit.result.MethodData

object SDKAdsKit {

    private const val LOG_PREFIX = "[SDKAdsKit]"

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

    private fun hookMethodsByStringMatch(cacheKey: String, strings: List<String>, action: (Method) -> Unit) {
        DexKitUtil.getCachedOrFindMethods(cacheKey) {
            DexKitUtil.getBridge().findMethod {
                matcher { usingStrings = strings }
            }
        }?.forEach { methodData ->
            if (isValidMethodData(methodData)) {
                val method = methodData.getMethodInstance(DexKitUtil.context.classLoader)
                action(method)
                XposedBridge.log("$LOG_PREFIX Hooked method: ${methodData}")
            }
        }
    }

    private fun isValidMethodData(methodData: MethodData): Boolean {
        return methodData.methodName != "<init>"
    }

    fun handlePangolinSDK() {
        hookMethodsByStringMatch(
            "$packageName:handlePangolinSDK",
            listOf("tt_sdk_settings_other")
        ) { method ->
            XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING)
        }
    }

    fun handlePangolinInit() {
        val ttAdSdkClass = try {
            Class.forName("com.bytedance.sdk.openadsdk.TTAdSdk", false, DexKitUtil.context.classLoader)
        } catch (e: ClassNotFoundException) {
            return
        }

        val methods = ttAdSdkClass.declaredMethods
        if (methods.any { it.name == "init" }) {
            hookAllMethods(
                "com.bytedance.sdk.openadsdk.TTAdSdk",
                "init",
                "after",
                { param ->
                    param.result = when ((param.method as java.lang.reflect.Method).returnType) {
                        Void.TYPE -> null
                        java.lang.Boolean.TYPE -> false
                        else -> null
                    }
                },
                DexKitUtil.context.classLoader
            )
        }
    }

    fun handleAnyThinkSDK() {
        hookMethodsByStringMatch(
            "$packageName:handleAnyThinkSDK",
            listOf("anythink_sdk")
        ) { method ->
            val replacement = when (method.returnType.name) {
                "void" -> XC_MethodReplacement.DO_NOTHING
                "boolean" -> XC_MethodReplacement.returnConstant(false)
                else -> null
            }
            replacement?.let { XposedBridge.hookMethod(method, it) }
        }
    }

    fun blockFirebaseWithString() {
        hookMethodsByStringMatch(
            "$packageName:blockFirebaseWithString",
            listOf("Device unlocked: initializing all Firebase APIs for app ")
        ) { method ->
            XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING)
        }
    }

    fun blockFirebaseWithString2() {
        hookMethodsByStringMatch(
            "$packageName:blockFirebaseWithString2",
            listOf("[DEFAULT]")
        ) { method ->
            hookMethod(method, "after") { param ->
                param.result = null
            }
        }
    }

    fun blockAdsWithBaseBundle() {
        findAndHookMethod(
            BaseBundle::class.java,
            "get",
            arrayOf(String::class.java),
            "after"
        ) { param ->
            val key = param.args[0] as? String
            if ("com.google.android.gms.ads.APPLICATION_ID" == key) {
                param.result = "ca-app-pub-0000000000000000~0000000000"
            }
        }
    }

    fun blockAdsWithString() {
        hookMethodsByStringMatch(
            "$packageName:blockAdsWithString",
            listOf("Flags.initialize() was not called!")
        ) { method ->
            hookMethod(method, "after") { param ->
                param.result = true
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

        DexKitUtil.getCachedOrFindMethods("$packageName:blockAdsWithPackageName") {
            DexKitUtil.getBridge().findMethod {
                searchPackages(adPackages)
                matcher {
                    modifiers = Modifier.PUBLIC
                    returnType(Void.TYPE)
                }
            }?.filter(::isValidAdMethod)
        }?.forEach { methodData ->
            val method = methodData.getMethodInstance(DexKitUtil.context.classLoader)
            XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING)
            XposedBridge.log("$LOG_PREFIX Hooked method: ${methodData}")
        }
    }

    private fun isValidAdMethod(methodData: MethodData): Boolean {
        return !Modifier.isAbstract(methodData.modifiers) &&
               methodData.methodName in listOf("loadAd", "loadAds", "load", "show", "fetchAd")
    }
}
