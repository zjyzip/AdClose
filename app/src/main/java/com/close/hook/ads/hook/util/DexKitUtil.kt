package com.close.hook.ads.hook.util

import android.content.Context
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.ref.WeakReference

object DexKitUtil {
    @Volatile private var bridge: DexKitBridge? = null
    private val methodCache = mutableMapOf<String, WeakReference<List<MethodData>>>()

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
        methodCache[packageName] = WeakReference(methods)
    }

    fun getCachedOrFindMethods(packageName: String, findMethodLogic: () -> List<MethodData>?): List<MethodData>? {
        return getCachedMethods(packageName) ?: findMethodLogic().also {
            it?.let { methods -> cacheMethods(packageName, methods) }
        }
    }
}
