package com.close.hook.ads.hook.util

import android.content.Context
import com.google.common.cache.CacheBuilder
import com.close.hook.ads.hook.HookInit
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.util.concurrent.TimeUnit

object DexKitUtil {
    @Volatile private var bridge: DexKitBridge? = null
    private val methodCache = CacheBuilder.newBuilder()
        .maximumSize(100)
        .expireAfterAccess(1, TimeUnit.HOURS)
        .build<String, List<MethodData>>()

    val context: Context
        get() = HookInit.globalContext

    @Synchronized
    fun initializeDexKitBridge() {
        if (bridge == null) {
            System.loadLibrary("dexkit")
            val apkPath = context.applicationInfo.sourceDir
            bridge = DexKitBridge.create(apkPath)
        }
    }

    fun getBridge(): DexKitBridge {
        return bridge ?: throw IllegalStateException("DexKitBridge not initialized")
    }

    @Synchronized
    fun releaseBridge() {
        bridge?.close()
        bridge = null
    }

    fun getCachedOrFindMethods(key: String, findMethodLogic: () -> List<MethodData>?): List<MethodData>? {
        return methodCache.get(key, findMethodLogic)
    }

}
