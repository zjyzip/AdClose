package com.close.hook.ads.hook.ha

import com.close.hook.ads.hook.util.HookUtil
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.model.HookMethodType
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

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
                                "after"
                            ) { param ->
                                param.setResult(parsedReturnValue)
                            }
                            XposedBridge.log("Custom Hook: findAndHookMethod - Class=${customConfig.className}, Method=${methodName}, Params=${customConfig.parameterTypes}, Return=${parsedReturnValue}")
                        } ?: XposedBridge.log("Custom Hook Error: Method names are null or empty for FIND_AND_HOOK_METHOD config: $customConfig")
                    }
                    HookMethodType.HOOK_ALL_METHODS -> {
                        customConfig.methodNames?.takeIf { it.isNotEmpty() }?.let {
                            val methodName = it[0]

                            HookUtil.hookAllMethods(
                                customConfig.className,
                                methodName,
                                "after"
                            ) { param ->
                                param.setResult(parsedReturnValue)
                            }
                            XposedBridge.log("Custom Hook: hookAllMethods - Class=${customConfig.className}, Method=${methodName}, Return=${parsedReturnValue}")
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
