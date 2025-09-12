package com.close.hook.ads.data.model

enum class HookMethodType(
    val displayName: String,
    val requiredFields: Set<HookField>,
    val optionalFields: Set<HookField> = emptySet()
) {
    HOOK_MULTIPLE_METHODS(
        displayName = "hookMultipleMethods",
        requiredFields = setOf(HookField.CLASS_NAME, HookField.METHOD_NAME),
        optionalFields = setOf(HookField.RETURN_VALUE)
    ),
    FIND_AND_HOOK_METHOD(
        displayName = "findAndHookMethod",
        requiredFields = setOf(HookField.CLASS_NAME, HookField.METHOD_NAME, HookField.PARAMETER_TYPES),
        optionalFields = setOf(HookField.RETURN_VALUE, HookField.HOOK_POINT, HookField.PARAMETER_REPLACEMENTS)
    ),
    HOOK_ALL_METHODS(
        displayName = "hookAllMethods",
        requiredFields = setOf(HookField.CLASS_NAME, HookField.METHOD_NAME),
        optionalFields = setOf(HookField.RETURN_VALUE, HookField.HOOK_POINT)
    ),
    SET_STATIC_OBJECT_FIELD(
        displayName = "setStaticObjectField",
        requiredFields = setOf(HookField.CLASS_NAME, HookField.FIELD_NAME),
        optionalFields = setOf(HookField.FIELD_VALUE)
    ),
    HOOK_METHODS_BY_STRING_MATCH(
        displayName = "通过字符串匹配 Hook 方法",
        requiredFields = setOf(HookField.SEARCH_STRINGS),
        optionalFields = setOf(HookField.RETURN_VALUE, HookField.HOOK_POINT)
    ),
    FIND_METHODS_WITH_STRING(
        displayName = "通过字符串和方法名查找 Hook 方法",
        requiredFields = setOf(HookField.SEARCH_STRINGS, HookField.METHOD_NAME),
        optionalFields = setOf(HookField.RETURN_VALUE, HookField.HOOK_POINT)
    ),
    REPLACE_CONTEXT_WITH_FAKE(
        displayName = "替换为伪造Context",
        requiredFields = setOf(HookField.CLASS_NAME, HookField.METHOD_NAME, HookField.PARAMETER_TYPES),
        optionalFields = setOf(HookField.HOOK_POINT)
    );

    val visibleFields: Set<HookField> = requiredFields + optionalFields

    companion object {
        fun fromDisplayName(displayName: String): HookMethodType? {
            return entries.firstOrNull { it.displayName == displayName }
        }
    }
}