package com.close.hook.ads.hook.util

import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.result.MethodData

object StringFinderKit {

    fun findMethodsWithString(key: String, searchString: String, methodName: String): List<MethodData>? {
        return try {
            DexKitUtil.initializeDexKitBridge()
            DexKitUtil.getCachedOrFindMethods(key) {
                DexKitUtil.getBridge().findMethod {
                    matcher {
                        usingStrings = listOf(searchString)
                        name = methodName
                    }
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("Error in findMethodsWithString: ${e.message}")
            XposedBridge.log(e)
            null
        } finally {
            DexKitUtil.releaseBridge()
        }
    }
}
