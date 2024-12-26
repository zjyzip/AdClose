package com.close.hook.ads.hook.util

import android.content.Context
import java.util.concurrent.TimeUnit
import com.google.common.cache.CacheBuilder
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

object DexKitUtil {
    private const val LOG_PREFIX = "[DexKitUtil]"

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
                val apkPath = context.applicationInfo.sourceDir
                bridge = DexKitBridge.create(apkPath)
                XposedBridge.log("$LOG_PREFIX DexKitBridge initialized successfully with classLoader: $classLoader")
            } catch (e: Throwable) {
                XposedBridge.log("$LOG_PREFIX Error initializing DexKitBridge: ${e.message}")
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
                XposedBridge.log("$LOG_PREFIX DexKitBridge released successfully.")
            } catch (e: Throwable) {
                XposedBridge.log("$LOG_PREFIX Error releasing DexKitBridge: ${e.message}")
            } finally {
                bridge = null
            }
        } else {
            XposedBridge.log("$LOG_PREFIX DexKitBridge already released or not initialized.")
        }
    }

    fun getCachedOrFindMethods(key: String, findMethodLogic: () -> List<MethodData>?): List<MethodData>? {
        return try {
            methodCache.get(key) {
                val result = findMethodLogic()
                if (result == null || result.isEmpty()) {
                    XposedBridge.log("$LOG_PREFIX No methods found for key: $key")
                } else {
                    XposedBridge.log("$LOG_PREFIX Methods found for key: $key -> ${result.size} methods cached.")
                }
                result
            }
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX Error retrieving methods for key: $key -> ${e.message}")
            null
        }
    }
}
