package com.close.hook.ads.hook.util

import org.luckypray.dexkit.result.MethodData

object StringFinderKit {

    fun findMethodsWithString(key: String, searchString: String, methodName: String): List<MethodData>? {
        DexKitUtil.initializeDexKitBridge()

        val foundMethods = DexKitUtil.getCachedOrFindMethods(key) {
            DexKitUtil.getBridge().findMethod {
            excludePackages(listOf("com"))
                matcher {
                    usingStrings = listOf(searchString)
                    name = methodName
                }
            }?.toList()
        }

        DexKitUtil.releaseBridge()
        return foundMethods
    }
}
