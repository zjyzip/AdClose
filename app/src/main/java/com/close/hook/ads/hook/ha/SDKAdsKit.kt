package com.close.hook.ads.hook.ha

import android.os.BaseBundle
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import de.robv.android.xposed.XposedBridge
import com.close.hook.ads.hook.util.DexKitUtil
import com.close.hook.ads.hook.util.HookUtil.findAndHookMethod
import com.close.hook.ads.hook.util.HookUtil.hookMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData

object SDKAdsKit {

    private const val ENABLE_DEBUG_LOG = false
    private const val LOG_PREFIX = "[SDKAdsKit]"

    private val packageName: String by lazy { DexKitUtil.context.packageName }

    fun hookAds() {
        handleIQIYI()
        blockAnyThinkNet()
        blockFirebaseWithString()
        blockAdIdWithString()
        blockAdIdWithBaseBundle()
        blockGoolgeAdsInitialize()
        blockAdsWithPackageName()
    }

    fun hookMethodsByStringMatch(cacheKey: String, strings: List<String>, action: (Method) -> Unit) {
        DexKitUtil.withBridge { bridge ->
            DexKitUtil.getCachedOrFindMethods(cacheKey) {
                bridge.findMethod {
                    matcher { usingStrings = strings }
                }
            }?.forEach { methodData ->
                if (isValidMethod(methodData)) {
                    val method = methodData.getMethodInstance(DexKitUtil.context.classLoader)
                    action(method)
                    if (ENABLE_DEBUG_LOG) XposedBridge.log("$LOG_PREFIX Hooked method: $methodData")
                }
            }
        }
    }

    fun handleIQIYI() {
        hookMethodsByStringMatch(
            "$packageName:handleIQIYI",
            listOf("smartUpgradeResponse")
        ) { method ->
            hookMethod(method, "before") { param ->
                param.result = null
            }
        }
    }

    fun blockFirebaseWithString() {
        hookMethodsByStringMatch(
            "$packageName:blockFirebaseWithString",
            listOf("Device unlocked: initializing all Firebase APIs for app ")
        ) { method ->
            hookMethod(method, "before") { param ->
                param.result = null
            }
        }
    }

    fun blockAdIdWithBaseBundle() {
        findAndHookMethod(
            BaseBundle::class.java,
            "get",
            arrayOf(String::class.java),
            "after",
            { param ->
                val key = param.args[0] as String
                if ("com.google.android.gms.ads.APPLICATION_ID" == key) {
                    param.result = "ca-app-pub-0000000000000000~0000000000"
                }
            }
        )
    }

    fun blockAnyThinkNet() {
        DexKitUtil.withBridge { bridge ->
            DexKitUtil.getCachedOrFindMethods("$packageName:blockAnyThinkNet") {
                bridge.findClass {
                    matcher {
                        usingStrings(listOf("anythink_default_thread"), StringMatchType.Equals)
                    }
                }.findMethod {
                    matcher {
                        returnType(Void.TYPE)
                        name = "run"
                    }
                }?.filter { isValidMethod(it) }
            }?.forEach { methodData ->
                val method = methodData.getMethodInstance(DexKitUtil.context.classLoader)
                if (ENABLE_DEBUG_LOG) XposedBridge.log("$LOG_PREFIX Hooked method: $methodData")
                hookMethod(method, "before") { param ->
                    param.result = null
                }
            }
        }
    }

    fun blockAdIdWithString() {
        DexKitUtil.withBridge { bridge ->
            DexKitUtil.getCachedOrFindMethods("$packageName:blockAdIdWithString") {
                bridge.findClass {
                    matcher {
                        usingStrings(listOf("ca-app-pub-"), StringMatchType.StartsWith)
                    }
                }.findMethod {
                    matcher {
                        returnType(Void.TYPE)
                    }
                }?.filter { isValidMethod(it) }
            }?.forEach { methodData ->
                val method = methodData.getMethodInstance(DexKitUtil.context.classLoader)
                if (ENABLE_DEBUG_LOG) XposedBridge.log("$LOG_PREFIX Hooked method: $methodData")
                hookMethod(method, "before") { param ->
                    param.result = null
                }
            }
        }
    }

    fun blockGoolgeAdsInitialize() {
        DexKitUtil.withBridge { bridge ->
            DexKitUtil.getCachedOrFindMethods("$packageName:blockGoolgeAdsInitialize") {
                bridge.findClass {
                    matcher {
                        className("com.google.android.gms.ads.MobileAds")
                    }
                }.findMethod {
                    matcher {
                        returnType(Void.TYPE)
                    }
                }?.filter { isValidMethod(it) }
            }?.forEach {
                val method = it.getMethodInstance(DexKitUtil.context.classLoader)
                if (ENABLE_DEBUG_LOG) XposedBridge.log("$LOG_PREFIX Hooked method: $it")
                hookMethod(method, "before") { param ->
                    param.result = null
                }
            }
        }
    }

    private val validAdMethods = setOf(
        "loadAd", "loadAds", "load", "show", "fetchAd",
        "initSDK", "initialize", "initializeSdk"
    )

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
            "com.vungle.warren",
        )

        DexKitUtil.withBridge { bridge ->
            DexKitUtil.getCachedOrFindMethods("$packageName:blockAdsWithPackageName") {
                bridge.findMethod {
                    searchPackages(adPackages)
                    matcher {
                        modifiers = Modifier.PUBLIC
                        returnType(Void.TYPE)
                    }
                }?.filter { isValidMethod(it, true) }
            }?.forEach {
                val method = it.getMethodInstance(DexKitUtil.context.classLoader)
                if (ENABLE_DEBUG_LOG) XposedBridge.log("$LOG_PREFIX Hooked method: $it")
                hookMethod(method, "before") { param ->
                    param.result = null
                }
            }
        }
    }

    private fun isValidMethod(methodData: MethodData, checkName: Boolean = false): Boolean {
        return methodData.name !in setOf("<init>", "<clinit>") &&
               !Modifier.isAbstract(methodData.modifiers) &&
               (!checkName || methodData.methodName in validAdMethods)
    }
}
