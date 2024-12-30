package com.close.hook.ads.hook.util

import android.content.Context
import com.google.common.cache.CacheBuilder
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object DexKitUtil {
    private const val LOG_PREFIX = "[DexKitUtil]"

    private val bridge = AtomicReference<DexKitBridge?>()
    private val methodCache = CacheBuilder.newBuilder()
        .maximumSize(300)
        .concurrencyLevel(4)
        .expireAfterWrite(12, TimeUnit.HOURS)
        .build<String, List<MethodData>?>()

    val context: Context
        get() = ContextUtil.applicationContext

    init {
        System.loadLibrary("dexkit")
    }

    fun initializeDexKitBridge() {
        bridge.get() ?: synchronized(this) {
            bridge.get() ?: run {
                try {
                    val classLoader = context.classLoader
                    val apkPath = context.applicationInfo.sourceDir
                    val newBridge = DexKitBridge.create(apkPath)
                    XposedBridge.log("$LOG_PREFIX DexKitBridge initialized successfully with classLoader: $classLoader")
                    if (bridge.compareAndSet(null, newBridge)) {
                        XposedBridge.log("$LOG_PREFIX DexKitBridge initialized successfully.")
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$LOG_PREFIX Error initializing DexKitBridge: ${e.message}")
                    throw RuntimeException("Failed to initialize DexKitBridge", e)
                }
            }
        }
    }

    fun getBridge(): DexKitBridge =
        bridge.get() ?: throw IllegalStateException("DexKitBridge not initialized")

    fun releaseBridge() {
        bridge.getAndSet(null)?.let {
            try {
                it.close()
                XposedBridge.log("$LOG_PREFIX DexKitBridge released successfully.")
            } catch (e: Throwable) {
                XposedBridge.log("$LOG_PREFIX Error releasing DexKitBridge: ${e.message}")
            }
        } ?: XposedBridge.log("$LOG_PREFIX DexKitBridge already released or not initialized.")
    }

    fun getCachedOrFindMethods(key: String, findMethodLogic: () -> List<MethodData>?): List<MethodData>? {
        return try {
            methodCache.get(key) {
                val result = findMethodLogic()
                if (result.isNullOrEmpty()) {
                    XposedBridge.log("$LOG_PREFIX No methods found for key: $key")
                } else {
                    XposedBridge.log("$LOG_PREFIX Methods cached for key: $key -> ${result.size} methods.")
                }
                result
            }
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX Error retrieving methods for key: $key -> ${e.message}")
            null
        }
    }
}
