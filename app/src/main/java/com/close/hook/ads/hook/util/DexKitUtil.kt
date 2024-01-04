package com.close.hook.ads.hook.util

import android.content.Context
import org.luckypray.dexkit.DexKitBridge

object DexKitUtil {
    private var bridge: DexKitBridge? = null

    fun initializeDexKitBridge(context: Context) {
        if (bridge == null) {
            System.loadLibrary("dexkit")
            bridge = DexKitBridge.create(context.applicationInfo.sourceDir)
        }
    }

    fun getBridge(): DexKitBridge {
        return bridge ?: throw IllegalStateException("DexKitBridge not initialized")
    }

    fun releaseBridge() {
        bridge?.close()
        bridge = null
    }
}
