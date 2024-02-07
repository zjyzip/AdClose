package com.close.hook.ads.hook.util

import org.luckypray.dexkit.result.MethodData

object StringFinderKit {

    fun findMethodsWithString(key: String, searchString: String): List<MethodData>? {
        DexKitUtil.initializeDexKitBridge()

        val foundMethods = DexKitUtil.getCachedOrFindMethods(key) {
            DexKitUtil.getBridge().findMethod {
                searchPackages(listOf("okhttp3"))
                matcher {
                    usingStrings = listOf(searchString)
                }
            }?.toList()
        }

        DexKitUtil.releaseBridge()
        return foundMethods
    }
}
