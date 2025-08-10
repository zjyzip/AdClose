package com.close.hook.ads.hook.ha

import android.content.Context
import android.app.AndroidAppHelper
import com.close.hook.ads.hook.util.HookUtil
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.model.HookMethodType
import com.close.hook.ads.hook.ha.SDKAdsKit
import com.close.hook.ads.hook.util.StringFinderKit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.NumberFormatException

object CustomHookAds {

    fun hookCustomAds(classLoader: ClassLoader, customConfigs: List<CustomHookInfo>, isScopeEnabled: Boolean) {
        if (!isScopeEnabled) {
            return
        }

        for (customConfig in customConfigs) {
            if (!customConfig.isEnabled) {
                continue
            }

            try {
                val parsedReturnValue = parseReturnValue(customConfig.returnValue)

                when (customConfig.hookMethodType) {
                    HookMethodType.HOOK_MULTIPLE_METHODS -> {
                        customConfig.methodNames?.takeIf { it.isNotEmpty() }?.let {
                            HookUtil.hookMultipleMethods(
                                classLoader,
                                customConfig.className,
                                it.toTypedArray(),
                                parsedReturnValue
                            )
                            XposedBridge.log("Custom Hook: hookMultipleMethods - Class=${customConfig.className}, Methods=${it}, Return=${parsedReturnValue}")
                        } ?: XposedBridge.log("Custom Hook Error: Method names are null or empty for HOOK_MULTIPLE_METHODS config: $customConfig")
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
                                },
                                classLoader
                            )
                            XposedBridge.log("Custom Hook: findAndHookMethod - Class=${customConfig.className}, Method=${methodName}, Params=${customConfig.parameterTypes}, HookPoint=${customConfig.hookPoint}, Return=${parsedReturnValue}")
                        } ?: XposedBridge.log("Custom Hook Error: Method names are null or empty for FIND_AND_HOOK_METHOD config: $customConfig")
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
                                },
                                classLoader
                            )
                            XposedBridge.log("Custom Hook: hookAllMethods - Class=${customConfig.className}, Method=${methodName}, HookPoint=${customConfig.hookPoint}, Return=${parsedReturnValue}")
                        } ?: XposedBridge.log("Custom Hook Error: Method names are null or empty for HOOK_ALL_METHODS config: $customConfig")
                    }
                    HookMethodType.SET_STATIC_OBJECT_FIELD -> {
                        customConfig.fieldName?.takeIf { it.isNotEmpty() }?.let { fieldName ->
                            val fieldParsedValue = parseReturnValue(customConfig.fieldValue)
                            HookUtil.setStaticObjectField(
                                customConfig.className,
                                classLoader,
                                fieldName,
                                fieldParsedValue
                            )
                            XposedBridge.log("Custom Hook: setStaticObjectField - Class=${customConfig.className}, Field=${fieldName}, Value=${fieldParsedValue}")
                        } ?: XposedBridge.log("Custom Hook Error: Field name is null or empty for SET_STATIC_OBJECT_FIELD config: $customConfig")
                    }
                    HookMethodType.HOOK_METHODS_BY_STRING_MATCH -> {
                        customConfig.searchStrings?.takeIf { it.isNotEmpty() }?.let { searchStrings ->
                            SDKAdsKit.hookMethodsByStringMatch(customConfig.id, searchStrings) { method ->
                                HookUtil.hookMethod(method, customConfig.hookPoint) { param ->
                                    param.result = parsedReturnValue
                                }
                                XposedBridge.log("Custom Hook: hookMethodsByStringMatch - ID=${customConfig.id}, Strings=${searchStrings}, HookPoint=${customConfig.hookPoint}, Return=${parsedReturnValue}, MethodData: $method")
                            }
                        } ?: XposedBridge.log("Custom Hook Error: Search strings are null or empty for HOOK_METHODS_BY_STRING_MATCH config: $customConfig")
                    }
                    HookMethodType.FIND_METHODS_WITH_STRING -> {
                        customConfig.searchStrings?.takeIf { it.isNotEmpty() }?.let { searchStrings ->
                            customConfig.methodNames?.takeIf { it.isNotEmpty() }?.let { methodNames ->
                                val targetMethodName = methodNames[0]
                                StringFinderKit.findMethodsWithString(customConfig.id, searchStrings[0], targetMethodName)?.forEach { methodData ->
                                    methodData.getMethodInstance(classLoader)?.let { method ->
                                        HookUtil.hookMethod(method, customConfig.hookPoint) { param ->
                                            param.result = parsedReturnValue
                                        }
                                        XposedBridge.log("Custom Hook: findMethodsWithString - ID=${customConfig.id}, String=${searchStrings[0]}, MethodName=${targetMethodName}, HookPoint=${customConfig.hookPoint}, Return=${parsedReturnValue}, FoundMethod=${methodData}")
                                    } ?: XposedBridge.log("Custom Hook Error: Could not get method instance for FIND_METHODS_WITH_STRING config: $customConfig, MethodData: $methodData")
                                }
                            } ?: XposedBridge.log("Custom Hook Error: Method name is null or empty for FIND_METHODS_WITH_STRING config: $customConfig")
                        } ?: XposedBridge.log("Custom Hook Error: Search strings are null or empty for FIND_METHODS_WITH_STRING config: $customConfig")
                    }
                    HookMethodType.REPLACE_CONTEXT_WITH_FAKE -> {
                        customConfig.methodNames?.takeIf { it.isNotEmpty() }?.let {
                            val methodName = it[0]
                            val paramTypes = resolveParameterTypes(customConfig.parameterTypes, classLoader)

                            HookUtil.findAndHookMethod(
                                customConfig.className,
                                methodName,
                                paramTypes,
                                customConfig.hookPoint,
                                { param ->
                                    param.result = FakeContextWrapper(AndroidAppHelper.currentApplication())
                                },
                                classLoader
                            )
                            XposedBridge.log("Custom Hook: REPLACE_CONTEXT_WITH_FAKE - Class=${customConfig.className}, Method=${methodName}, Params=${customConfig.parameterTypes}")
                        } ?: XposedBridge.log("Custom Hook Error: Method names are null or empty for REPLACE_CONTEXT_WITH_FAKE config: $customConfig")
                    }
                }
            } catch (e: Throwable) {
                XposedBridge.log("Custom Hook Error for config ${customConfig.className} - ${customConfig.methodNames}: ${e.message}")
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
            else -> try {
                Integer.parseInt(trimmedValue)
            } catch (e: NumberFormatException) {
                try {
                    java.lang.Long.parseLong(trimmedValue)
                } catch (ex: NumberFormatException) {
                    try {
                        java.lang.Float.parseFloat(trimmedValue)
                    } catch (exc: NumberFormatException) {
                        try {
                            java.lang.Double.parseDouble(trimmedValue)
                        } catch (exc2: NumberFormatException) {
                            trimmedValue
                        }
                    }
                }
            }
        }
    }

    private fun resolveParameterTypes(paramTypeNames: List<String>?, classLoader: ClassLoader): Array<Any?> {
        if (paramTypeNames.isNullOrEmpty()) return emptyArray()
        return paramTypeNames.map { typeName ->
            when (typeName.trim()) {
                "boolean" -> java.lang.Boolean.TYPE
                "byte" -> java.lang.Byte.TYPE
                "short" -> java.lang.Short.TYPE
                "int" -> java.lang.Integer.TYPE
                "long" -> java.lang.Long.TYPE
                "float" -> java.lang.Float.TYPE
                "double" -> java.lang.Double.TYPE
                "char" -> java.lang.Character.TYPE
                "void" -> java.lang.Void.TYPE
                else -> XposedHelpers.findClassIfExists(typeName.trim(), classLoader)
            }
        }.toTypedArray()
    }
}
