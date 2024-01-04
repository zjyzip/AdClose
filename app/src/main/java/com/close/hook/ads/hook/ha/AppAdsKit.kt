package com.close.hook.ads.hook.ha

import android.content.Context
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import de.robv.android.xposed.XposedBridge
import com.close.hook.ads.hook.util.DexKitUtil
import org.luckypray.dexkit.result.MethodData
import de.robv.android.xposed.XC_MethodReplacement

object AppAdsKit {
    private val executor = Executors.newSingleThreadExecutor()

    fun blockAds(context: Context) {
        val packageName = context.packageName
        val zhihuPackage = "com.zhihu.android.app.util"
        val methodName = "isShowLaunchAd"

        DexKitUtil.initializeDexKitBridge(context)
        val cachedMethods = DexKitUtil.getCachedMethods(packageName)

        if (cachedMethods == null) {
            executor.execute {
                val foundMethods = DexKitUtil.getBridge().findMethod {
                    searchPackages(listOf(zhihuPackage))
                    matcher {
                        modifiers = Modifier.PUBLIC
                        returnType(java.lang.Boolean.TYPE)
                        name = methodName
                    }
                }?.toList()
                foundMethods?.let { 
                    DexKitUtil.cacheMethods(packageName, it)
                    hookZhihuMethods(it, context.classLoader)
                }
                DexKitUtil.releaseBridge()
            }
        } else {
            hookZhihuMethods(cachedMethods, context.classLoader)
        }
    }

    private fun hookZhihuMethods(methods: List<MethodData>, classLoader: ClassLoader) {
        methods.forEach { methodData ->
            try {
                val method = methodData.getMethodInstance(classLoader)
                XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(false))
            } catch (e: NoClassDefFoundError) {
            }
        }
    }
}
