package com.close.hook.ads.hook.util

import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.ref.WeakReference

object DexKitUtil {
    @Volatile private var bridge: DexKitBridge? = null
    private val methodCache = mutableMapOf<String, WeakReference<List<MethodData>>>()
    private const val CACHE_SIZE_LIMIT = 50

    fun initializeDexKitBridge(context: Context) {
        if (bridge == null) {
            synchronized(this) {
                if (bridge == null) {
                    System.loadLibrary("dexkit")
                    bridge = DexKitBridge.create(context.applicationInfo.sourceDir)
                }
            }
        }
    }

    fun getBridge(): DexKitBridge {
        return bridge ?: throw IllegalStateException("DexKitBridge not initialized")
    }

    fun releaseBridge() {
        synchronized(this) {
            bridge?.close()
            bridge = null
        }
    }

    fun getCachedMethods(packageName: String): List<MethodData>? {
        return methodCache[packageName]?.get()
    }

    fun cacheMethods(packageName: String, methods: List<MethodData>) {
        if (methodCache.size >= CACHE_SIZE_LIMIT) {
            methodCache.clear()
        }
        methodCache[packageName] = WeakReference(methods)
    }
}
