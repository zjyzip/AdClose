package com.close.hook.ads.hook.ha

import java.lang.reflect.Modifier
import de.robv.android.xposed.XposedBridge
import com.close.hook.ads.hook.util.DexKitUtil
import org.luckypray.dexkit.result.MethodData
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

        val cacheKey = "$packageName:blockAds"
        val foundMethods = DexKitUtil.getCachedOrFindMethods(cacheKey) {
            DexKitUtil.getBridge().findMethod {
                searchPackages(adPackages)
                matcher {
                    modifiers = Modifier.PUBLIC
                    returnType(Void.TYPE)
                }
            }?.filter { isValidAdMethod(it) }?.toList()
        }
        
        foundMethods?.let { hookMethods(it, DexKitUtil.context.classLoader) }
        DexKitUtil.releaseBridge()
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
    //          XposedBridge.log("hook $methodData")
            } catch (e: NoClassDefFoundError) {
            }
        }
    }
}
