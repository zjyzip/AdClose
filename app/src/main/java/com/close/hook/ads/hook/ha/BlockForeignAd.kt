package com.close.hook.ads.hook.ha

import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData;
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XC_MethodReplacement

object BlockForeignAd {
    private var bridge: DexKitBridge? = null
    private val methodCache = mutableMapOf<String, List<MethodData>>()

    fun blockAds(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        val packages = listOf(
            "com.applovin",
            "com.facebook.ads",
            "com.fyber.inneractive.sdk",
            "com.google.android.gms.ads",
            "com.inmobi.media",
            "com.mbridge.msdk",
            "com.smaato.sdk",
            "com.tp.adx",
            "com.tradplus.ads",
            "com.unity3d.services",
            "com.unity3d.ads",
            "com.vungle.warren"
        )

        if (bridge == null) {
            System.loadLibrary("dexkit")
            bridge = DexKitBridge.create(lpparam.appInfo.sourceDir)
        }

        val cachedMethods = methodCache[packageName]
        if (cachedMethods == null) {
            bridge?.let { dexKitBridge ->
                val foundMethods = dexKitBridge.findMethod {
                    searchPackages(packages)
                    matcher {
                        modifiers = Modifier.PUBLIC
                        returnType(Void.TYPE)
                    }
                }.filter { methodData ->
                    !Modifier.isAbstract(methodData.modifiers) && 
                    methodData.methodName in listOf("loadAd", "loadAds", "load", "show", "fetchAd")
                }.toList()

                methodCache[packageName] = foundMethods
                hookMethods(foundMethods, lpparam)
            }
        } else {
            hookMethods(cachedMethods, lpparam)
        }
    }

    private fun hookMethods(methods: List<MethodData>, lpparam: XC_LoadPackage.LoadPackageParam) {
        methods.forEach { methodData ->
            try {
                val method = methodData.getMethodInstance(lpparam.classLoader)
                XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING)
//                XposedBridge.log("hook $methodData")
            } catch (e: NoClassDefFoundError) {
            }
        }
    }

    fun releaseBridge() {
        bridge?.close()
        bridge = null
        methodCache.clear()
    }
}
