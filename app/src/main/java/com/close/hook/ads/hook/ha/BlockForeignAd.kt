package com.close.hook.ads.hook.ha

import android.content.Context
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XC_MethodReplacement

object BlockForeignAd {
    private var bridge: DexKitBridge? = null
    private val methodCache = mutableMapOf<String, List<MethodData>>()

    fun blockAds(context: Context) {
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

        if (!methodCache.containsKey(packageName)) {
            initializeDexKitBridge(context)
            findAndCacheMethods(packageName, context.classLoader, adPackages)
        }
        methodCache[packageName]?.let { hookMethods(it, context.classLoader) }
    }

    private fun initializeDexKitBridge(context: Context) {
        if (bridge == null) {
            System.loadLibrary("dexkit")
            bridge = DexKitBridge.create(context.applicationInfo.sourceDir)
        }
    }

    private fun findAndCacheMethods(packageName: String, classLoader: ClassLoader, adPackages: List<String>) {
        val foundMethods = bridge?.findMethod {
            searchPackages(adPackages)
            matcher {
                modifiers = Modifier.PUBLIC
                returnType(Void.TYPE)
            }
        }?.filter { methodData ->
            isValidAdMethod(methodData)
        }?.toList()

        foundMethods?.let { methodCache[packageName] = it }
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

    fun releaseBridge() {
        bridge?.close()
        bridge = null
        methodCache.clear()
    }
}
