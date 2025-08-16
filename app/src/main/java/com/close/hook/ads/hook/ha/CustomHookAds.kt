package com.close.hook.ads.hook.ha

import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.model.HookMethodType
import com.close.hook.ads.hook.util.HookUtil
import com.close.hook.ads.hook.util.LogProxy
import com.close.hook.ads.hook.util.StringFinderKit
import de.robv.android.xposed.XposedHelpers
import java.lang.NumberFormatException

object CustomHookAds {

    private const val TAG = "CustomHookAds"

    fun hookCustomAds(classLoader: ClassLoader, customConfigs: List<CustomHookInfo>, isScopeEnabled: Boolean) {
        if (!isScopeEnabled) return

        for (customConfig in customConfigs) {
            if (!customConfig.isEnabled) continue

            try {
                val parsedReturnValue = parseReturnValue(customConfig.returnValue)

                when (customConfig.hookMethodType) {
                    HookMethodType.HOOK_MULTIPLE_METHODS -> {
                        customConfig.methodNames?.takeIf { it.isNotEmpty() }?.let {
                            HookUtil.hookMultipleMethods(classLoader, customConfig.className, it.toTypedArray(), parsedReturnValue)
                            val message = "hookMultipleMethods - Class=${customConfig.className}, Methods=${it}, Return=${parsedReturnValue}"
                            LogProxy.log(TAG, message) // No callback, so no runtime stack trace
                        } ?: LogProxy.log(TAG, "Error: Method names are null or empty for HOOK_MULTIPLE_METHODS config: $customConfig")
                    }
                    HookMethodType.FIND_AND_HOOK_METHOD -> {
                        customConfig.methodNames?.takeIf { it.isNotEmpty() }?.let {
                            val methodName = it[0]
                            val paramTypes = resolveParameterTypes(customConfig.parameterTypes, classLoader)
                            HookUtil.findAndHookMethod(
                                customConfig.className,
                                methodName,
                                paramTypes,
                                customConfig.hookPoint,
                                { param ->
                                    param.result = parsedReturnValue
                                    val message = "findAndHookMethod - Class=${customConfig.className}, Method=${methodName}, Params=${customConfig.parameterTypes}, HookPoint=${customConfig.hookPoint}, Return=${parsedReturnValue}"
                                    LogProxy.log(TAG, message, HookUtil.getFormattedStackTrace())
                                },
                                classLoader
                            )
                        } ?: LogProxy.log(TAG, "Error: Method names are null or empty for FIND_AND_HOOK_METHOD config: $customConfig")
                    }
                    HookMethodType.HOOK_ALL_METHODS -> {
                        customConfig.methodNames?.takeIf { it.isNotEmpty() }?.let {
                            val methodName = it[0]
                            HookUtil.hookAllMethods(
                                customConfig.className,
                                methodName,
                                customConfig.hookPoint,
                                { param ->
                                    param.result = parsedReturnValue
                                    val message = "hookAllMethods - Class=${customConfig.className}, Method=${methodName}, HookPoint=${customConfig.hookPoint}, Return=${parsedReturnValue}"
                                    LogProxy.log(TAG, message, HookUtil.getFormattedStackTrace())
                                },
                                classLoader
                            )
                        } ?: LogProxy.log(TAG, "Error: Method names are null or empty for HOOK_ALL_METHODS config: $customConfig")
                    }
                    HookMethodType.SET_STATIC_OBJECT_FIELD -> {
                        customConfig.fieldName?.takeIf { it.isNotEmpty() }?.let { fieldName ->
                            val fieldParsedValue = parseReturnValue(customConfig.fieldValue)
                            HookUtil.setStaticObjectField(customConfig.className, classLoader, fieldName, fieldParsedValue)
                            val message = "setStaticObjectField - Class=${customConfig.className}, Field=${fieldName}, Value=${fieldParsedValue}"
                            LogProxy.log(TAG, message) // No callback, so no runtime stack trace
                        } ?: LogProxy.log(TAG, "Error: Field name is null or empty for SET_STATIC_OBJECT_FIELD config: $customConfig")
                    }
                    HookMethodType.HOOK_METHODS_BY_STRING_MATCH -> {
                        customConfig.searchStrings?.takeIf { it.isNotEmpty() }?.let { searchStrings ->
                            SDKAdsKit.hookMethodsByStringMatch(customConfig.id, searchStrings) { method ->
                                HookUtil.hookMethod(method, customConfig.hookPoint) { param ->
                                    param.result = parsedReturnValue
                                    val message = "hookMethodsByStringMatch - ID=${customConfig.id}, Strings=${searchStrings}, HookPoint=${customConfig.hookPoint}, Return=${parsedReturnValue}, MethodData: $method"
                                    LogProxy.log(TAG, message, HookUtil.getFormattedStackTrace())
                                }
                            }
                        } ?: LogProxy.log(TAG, "Error: Search strings are null or empty for HOOK_METHODS_BY_STRING_MATCH config: $customConfig")
                    }
                    HookMethodType.FIND_METHODS_WITH_STRING -> {
                        customConfig.searchStrings?.takeIf { it.isNotEmpty() }?.let { searchStrings ->
                            customConfig.methodNames?.takeIf { it.isNotEmpty() }?.let { methodNames ->
                                val targetMethodName = methodNames[0]
                                StringFinderKit.findMethodsWithString(customConfig.id, searchStrings[0], targetMethodName)?.forEach { methodData ->
                                    methodData.getMethodInstance(classLoader)?.let { method ->
                                        HookUtil.hookMethod(method, customConfig.hookPoint) { param ->
                                            param.result = parsedReturnValue
                                            val message = "findMethodsWithString - ID=${customConfig.id}, String=${searchStrings[0]}, MethodName=${targetMethodName}, HookPoint=${customConfig.hookPoint}, Return=${parsedReturnValue}, FoundMethod=${methodData}"
                                            LogProxy.log(TAG, message, HookUtil.getFormattedStackTrace())
                                        }
                                    } ?: LogProxy.log(TAG, "Error: Could not get method instance for FIND_METHODS_WITH_STRING config: $customConfig, MethodData: $methodData")
                                }
                            } ?: LogProxy.log(TAG, "Error: Method name is null or empty for FIND_METHODS_WITH_STRING config: $customConfig")
                        } ?: LogProxy.log(TAG, "Error: Search strings are null or empty for FIND_METHODS_WITH_STRING config: $customConfig")
                    }
                    HookMethodType.REPLACE_CONTEXT_WITH_FAKE -> {
                        customConfig.methodNames?.takeIf { it.isNotEmpty() }?.let {
                        // 不开放内容
                        } ?: LogProxy.log(TAG, "Error: Method names are null or empty for REPLACE_CONTEXT_WITH_FAKE config: $customConfig")
                    }
                }
            } catch (e: Throwable) {
                val errorMessage = "Error for config ${customConfig.className} - ${customConfig.methodNames}: ${e.message}"
                LogProxy.log(TAG, errorMessage, HookUtil.getFormattedStackTrace())
            }
        }
    }

    private fun parseReturnValue(valueStr: String?): Any? {
        if (valueStr.isNullOrBlank()) return null
        val trimmedValue = valueStr.trim()
        return when {
            trimmedValue.equals("true", ignoreCase = true) -> true
            trimmedValue.equals("false", ignoreCase = true) -> false
            trimmedValue.equals("null", ignoreCase = true) -> null
            else -> try { Integer.parseInt(trimmedValue) } catch (e: NumberFormatException) {
                try { java.lang.Long.parseLong(trimmedValue) } catch (ex: NumberFormatException) {
                    try { java.lang.Float.parseFloat(trimmedValue) } catch (exc: NumberFormatException) {
                        try { java.lang.Double.parseDouble(trimmedValue) } catch (exc2: NumberFormatException) { trimmedValue }
                    }
                }
            }
        }
    }

    private fun resolveParameterTypes(paramTypeNames: List<String>?, classLoader: ClassLoader): Array<Any?> {
        if (paramTypeNames.isNullOrEmpty()) return emptyArray()
        return paramTypeNames.map { typeName ->
            when (val trimmedName = typeName.trim()) {
                "boolean" -> java.lang.Boolean.TYPE
                "byte" -> java.lang.Byte.TYPE
                "short" -> java.lang.Short.TYPE
                "int" -> java.lang.Integer.TYPE
                "long" -> java.lang.Long.TYPE
                "float" -> java.lang.Float.TYPE
                "double" -> java.lang.Double.TYPE
                "char" -> java.lang.Character.TYPE
                "void" -> java.lang.Void.TYPE
                else -> XposedHelpers.findClassIfExists(trimmedName, classLoader)
            }
        }.toTypedArray()
    }
}
