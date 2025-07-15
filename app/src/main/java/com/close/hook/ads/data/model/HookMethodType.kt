package com.close.hook.ads.data.model

enum class HookMethodType(val displayName: String) {
    HOOK_MULTIPLE_METHODS("hookMultipleMethods"),
    FIND_AND_HOOK_METHOD("findAndHookMethod"),
    HOOK_ALL_METHODS("hookAllMethods"),
    SET_STATIC_OBJECT_FIELD("setStaticObjectField"),
    HOOK_METHODS_BY_STRING_MATCH("通过字符串匹配 Hook 方法"),
    FIND_METHODS_WITH_STRING("通过字符串和方法名查找 Hook 方法");

    companion object {
        fun fromDisplayName(displayName: String): HookMethodType? {
            return values().firstOrNull { it.displayName == displayName }
        }
    }
}
