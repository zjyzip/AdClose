package com.close.hook.ads.data.model

enum class HookMethodType(val displayName: String) {
    HOOK_MULTIPLE_METHODS("hookMultipleMethods"),
    FIND_AND_HOOK_METHOD("findAndHookMethod"),
    HOOK_ALL_METHODS("hookAllMethods"),
    SET_STATIC_OBJECT_FIELD("setStaticObjectField");

    override fun toString(): String {
        return displayName
    }
}
