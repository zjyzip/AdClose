package com.close.hook.ads.hook.ha

import android.content.Context
import java.lang.reflect.Modifier
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XC_MethodReplacement
import com.close.hook.ads.hook.util.DexKitUtil
import org.luckypray.dexkit.result.MethodData

object AppAdsKit {

    fun blockAds(context: Context) {
        DexKitUtil.initializeDexKitBridge(context)

        val packageName = context.packageName
        val zhihuPackage = "com.zhihu.android.app.util"
        val methodName = "isShowLaunchAd"

        val foundMethods = DexKitUtil.getCachedOrFindMethods(packageName) {
            DexKitUtil.getBridge().findMethod {
                searchPackages(listOf(zhihuPackage))
                matcher {
                    modifiers = Modifier.PUBLIC
                    returnType(java.lang.Boolean.TYPE)
                    name = methodName
                }
            }?.toList()
        }

        foundMethods?.let { hookZhihuMethods(it, context.classLoader) }
        DexKitUtil.releaseBridge()
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

