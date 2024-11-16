package com.close.hook.ads.hook.util

import android.content.Context
import java.util.concurrent.TimeUnit
import com.google.common.cache.CacheBuilder
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

object DexKitUtil {
    @Volatile
    private var bridge: DexKitBridge? = null
    private val methodCache = CacheBuilder.newBuilder()
        .maximumSize(200)
        .expireAfterWrite(12, TimeUnit.HOURS)
        .build<String, List<MethodData>?>()

    val context: Context
        get() = ContextUtil.appContext

    init {
        System.loadLibrary("dexkit")
    }

    @Synchronized
    fun initializeDexKitBridge() {
        if (bridge == null) {
            try {
                val classLoader = context.classLoader
                bridge = DexKitBridge.create(classLoader, true)
                XposedBridge.log("DexKitBridge initialized successfully with classLoader: $classLoader")
            } catch (e: Throwable) {
                XposedBridge.log("Error initializing DexKitBridge: ${e.message}")
                throw RuntimeException("Failed to initialize DexKitBridge", e)
            }
        }
    }

    fun getBridge(): DexKitBridge {
        return bridge ?: throw IllegalStateException("DexKitBridge not initialized")
    }

    @Synchronized
    fun releaseBridge() {
        if (bridge != null) {
            try {
                bridge?.close()
                XposedBridge.log("DexKitBridge released successfully.")
            } catch (e: Throwable) {
                XposedBridge.log("Error releasing DexKitBridge: ${e.message}")
            } finally {
                bridge = null
            }
        }
    }

    fun getCachedOrFindMethods(key: String, findMethodLogic: () -> List<MethodData>?): List<MethodData>? {
        return methodCache.get(key, findMethodLogic)
    }
}
