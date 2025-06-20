package com.close.hook.ads.hook.util

import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.result.MethodData

object StringFinderKit {

    fun findMethodsWithString(key: String, searchString: String, methodName: String): List<MethodData>? {
        return DexKitUtil.withBridge { bridge ->
            DexKitUtil.getCachedOrFindMethods(key) {
                bridge.findMethod {
                    matcher {
                        usingStrings = listOf(searchString)
                        name = methodName
                    }
                }
            }
        }
    }
}
