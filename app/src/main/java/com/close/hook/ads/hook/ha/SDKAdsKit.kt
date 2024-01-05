package com.close.hook.ads.hook.ha

import android.content.Context
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import de.robv.android.xposed.XposedBridge
import com.close.hook.ads.hook.util.DexKitUtil
import org.luckypray.dexkit.result.MethodData
import de.robv.android.xposed.XC_MethodReplacement

object SDKAdsKit {
    private val executor = Executors.newSingleThreadExecutor()

    fun blockAds(context: Context) {
        DexKitUtil.initializeDexKitBridge(context)
        val packageName = context.packageName
        val adPackages = listOf(
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

        val cachedMethods = DexKitUtil.getCachedMethods(packageName)
        if (cachedMethods == null) {
            executor.execute {
                val foundMethods = DexKitUtil.getBridge().findMethod {
                    searchPackages(adPackages)
                    matcher {
                        modifiers = Modifier.PUBLIC
                        returnType(Void.TYPE)
                    }
                }?.filter { methodData ->
                    isValidAdMethod(methodData)
                }?.toList()
                foundMethods?.let {
                    DexKitUtil.cacheMethods(packageName, it)
                    hookMethods(it, context.classLoader)
                }
            }
        } else {
            hookMethods(cachedMethods, context.classLoader)
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
