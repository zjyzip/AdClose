package com.close.hook.ads.hook.ha

import android.content.Context
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.model.HookMethodType
import com.close.hook.ads.hook.util.HookUtil
import com.close.hook.ads.hook.util.LogProxy
import com.close.hook.ads.hook.util.StringFinderKit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

object CustomHookAds {

    private const val TAG = "CustomHookAds"

    fun hookCustomAds(classLoader: ClassLoader, customConfigs: List<CustomHookInfo>, isScopeEnabled: Boolean) {
        if (!isScopeEnabled) return

        customConfigs.forEach { config ->
            if (config.isEnabled) {
                try {
                    applyHookConfig(config, classLoader)
                } catch (e: Throwable) {
                    LogProxy.log(TAG, "Error applying config for ${config.className}: ${e.message}")
                }
            }
        }
    }

    private fun applyHookConfig(config: CustomHookInfo, classLoader: ClassLoader) {
        val parsedReturnValue = parseReturnValue(config.returnValue)
        val preParsedParamReplacements = config.parameterReplacements?.mapValues { parseReturnValue(it.value) }

        when (config.hookMethodType) {
            HookMethodType.HOOK_MULTIPLE_METHODS -> handleHookMultipleMethods(config, classLoader, parsedReturnValue)
            HookMethodType.FIND_AND_HOOK_METHOD -> handleFindAndHookMethod(config, classLoader, parsedReturnValue, preParsedParamReplacements)
            HookMethodType.HOOK_ALL_METHODS -> handleHookAllMethods(config, classLoader, parsedReturnValue, preParsedParamReplacements)
            HookMethodType.SET_STATIC_OBJECT_FIELD -> handleSetStaticField(config, classLoader)
            HookMethodType.HOOK_METHODS_BY_STRING_MATCH -> handleHookByStringMatch(config, parsedReturnValue)
            HookMethodType.FIND_METHODS_WITH_STRING -> handleFindMethodWithString(config, classLoader, parsedReturnValue)
            HookMethodType.REPLACE_CONTEXT_WITH_FAKE -> handleReplaceContext(config, classLoader)
        }
    }

    private fun handleHookMultipleMethods(config: CustomHookInfo, classLoader: ClassLoader, returnValue: Any?) {
        val methodNames = config.methodNames ?: return
        if (methodNames.isEmpty()) return

        HookUtil.hookMultipleMethods(classLoader, config.className, methodNames.toTypedArray(), returnValue)
        LogProxy.log(TAG, "hookMultipleMethods - Class=${config.className}, Methods=$methodNames, Return=$returnValue")
    }

    private fun handleFindAndHookMethod(
        config: CustomHookInfo, 
        classLoader: ClassLoader, 
        returnValue: Any?, 
        paramReplacements: Map<Int, Any?>?
    ) {
        val methodName = config.methodNames?.firstOrNull() ?: return
        val paramTypes = resolveParameterTypes(config.parameterTypes, classLoader)
        
        HookUtil.findAndHookMethod(config.className, methodName, paramTypes, config.hookPoint, { param ->
            handleHookedMethod(param, config, returnValue, paramReplacements)
        }, classLoader)
    }

    private fun handleHookAllMethods(
        config: CustomHookInfo, 
        classLoader: ClassLoader, 
        returnValue: Any?, 
        paramReplacements: Map<Int, Any?>?
    ) {
        val methodName = config.methodNames?.firstOrNull() ?: return
        
        HookUtil.hookAllMethods(config.className, methodName, config.hookPoint, { param ->
            handleHookedMethod(param, config, returnValue, paramReplacements)
            LogProxy.log(TAG, "hookAllMethods - Class=${config.className}, Method=$methodName", HookUtil.getFormattedStackTrace())
        }, classLoader)
    }

    private fun handleSetStaticField(config: CustomHookInfo, classLoader: ClassLoader) {
        val fieldName = config.fieldName
        if (fieldName.isNullOrEmpty()) return

        val fieldValue = parseReturnValue(config.fieldValue)
        HookUtil.setStaticObjectField(config.className, classLoader, fieldName, fieldValue)
        LogProxy.log(TAG, "setStaticObjectField - Class=${config.className}, Field=$fieldName, Value=$fieldValue")
    }

    private fun handleHookByStringMatch(config: CustomHookInfo, returnValue: Any?) {
        val searchStrings = config.searchStrings ?: return
        if (searchStrings.isEmpty()) return

        SDKAdsKit.hookMethodsByStringMatch(config.id, searchStrings) { method ->
            HookUtil.hookMethod(method, config.hookPoint) { param ->
                param.result = returnValue
                LogProxy.log(TAG, "hookMethodsByStringMatch - ID=${config.id}, Method=$method", HookUtil.getFormattedStackTrace())
            }
        }
    }

    private fun handleFindMethodWithString(config: CustomHookInfo, classLoader: ClassLoader, returnValue: Any?) {
        val searchString = config.searchStrings?.firstOrNull() ?: return
        val methodName = config.methodNames?.firstOrNull() ?: return

        StringFinderKit.findMethodsWithString(config.id, searchString, methodName)?.forEach { methodData ->
            methodData.getMethodInstance(classLoader)?.let { method ->
                HookUtil.hookMethod(method, config.hookPoint) { param ->
                    param.result = returnValue
                    LogProxy.log(TAG, "findMethodsWithString - ID=${config.id}, Found=$method", HookUtil.getFormattedStackTrace())
                }
            }
        }
    }

    private fun handleReplaceContext(config: CustomHookInfo, classLoader: ClassLoader) {
        val methodName = config.methodNames?.firstOrNull() ?: return
        val paramTypes = resolveParameterTypes(config.parameterTypes, classLoader)
        
        HookUtil.findAndHookMethod(config.className, methodName, paramTypes, config.hookPoint, { param ->
            handleReplaceContextWithFake(param, config)
        }, classLoader)
    }

    private fun handleHookedMethod(
        param: XC_MethodHook.MethodHookParam, 
        config: CustomHookInfo, 
        returnValue: Any?, 
        paramReplacements: Map<Int, Any?>?
    ) {
        val fullMethodName = "${config.className}.${config.methodNames?.firstOrNull() ?: "unknown"}"

        if (!paramReplacements.isNullOrEmpty()) {
            paramReplacements.forEach { (index, value) ->
                if (index >= 0 && index < param.args.size) {
                    param.args[index] = value
                    LogProxy.log(TAG, "Replaced parameter at index $index with value '$value' for method: $fullMethodName", HookUtil.getFormattedStackTrace())
                }
            }
            return 
        }

        if (config.returnValue != null) {
            param.result = returnValue
            LogProxy.log(TAG, "Set return value to '$returnValue' for method: $fullMethodName", HookUtil.getFormattedStackTrace())
            return
        }

        val method = param.method as? Method ?: return
        
        if (method.returnType == method.declaringClass) {
            val paramClasses = method.parameterTypes
            val hasInterfaceParam = paramClasses.any { it.isInterface }
            if (hasInterfaceParam) {
                param.result = null
                LogProxy.log(TAG, "Builder method has interface param, returned null to interrupt chain: $fullMethodName", HookUtil.getFormattedStackTrace())
            } else {
                LogProxy.log(TAG, "Builder method has no interface param, skip original method body: $fullMethodName", HookUtil.getFormattedStackTrace())
            }
            return
        }

        val paramClasses = method.parameterTypes
        param.args.forEachIndexed { i, arg ->
            if (arg != null && i < paramClasses.size) {
                val type = paramClasses[i]
                val (defaultVal, typeName) = when {
                    type == Boolean::class.javaPrimitiveType -> false to "Boolean"
                    type == Int::class.javaPrimitiveType -> 0 to "Int"
                    type == Long::class.javaPrimitiveType -> 0L to "Long"
                    type == Float::class.javaPrimitiveType -> 0.0f to "Float"
                    type == Double::class.javaPrimitiveType -> 0.0 to "Double"
                    else -> null to null
                }
                
                if (typeName != null) {
                    param.args[i] = defaultVal
                    LogProxy.log(TAG, "Replaced $typeName parameter at index $i with '$defaultVal' for method: $fullMethodName", HookUtil.getFormattedStackTrace())
                }
            }
        }
    }

    private fun handleReplaceContextWithFake(param: XC_MethodHook.MethodHookParam, config: CustomHookInfo) {
        val currentAppClass = XposedHelpers.findClass("android.app.ActivityThread", null) ?: return
        val realAppContext = XposedHelpers.callStaticMethod(currentAppClass, "currentApplication") as? Context ?: return

        val fakeContext = SmartFakeContext(realAppContext)
        val fullMethodName = "${config.className}.${config.methodNames?.firstOrNull()}"
        val method = param.method as Method

        if (Context::class.java.isAssignableFrom(method.returnType)) {
            param.result = fakeContext
            LogProxy.log(TAG, "Replaced Context return value with FakeContext for method: $fullMethodName", HookUtil.getFormattedStackTrace())
        }

        param.args.forEachIndexed { i, arg ->
            if (arg is Context) {
                param.args[i] = fakeContext
                LogProxy.log(TAG, "Replaced Context parameter at index $i with FakeContext for method: $fullMethodName", HookUtil.getFormattedStackTrace())
            }
        }
    }

    private fun parseReturnValue(valueStr: String?): Any? {
        val trimmed = valueStr?.trim() ?: return null
        if (trimmed.equals("null", ignoreCase = true)) return null
        if (trimmed.equals("true", ignoreCase = true)) return true
        if (trimmed.equals("false", ignoreCase = true)) return false

        return trimmed.toIntOrNull()
            ?: trimmed.toLongOrNull()
            ?: trimmed.toFloatOrNull()
            ?: trimmed.toDoubleOrNull()
            ?: trimmed
    }

    private fun resolveParameterTypes(paramTypeNames: List<String>?, classLoader: ClassLoader): Array<Any> {
        if (paramTypeNames.isNullOrEmpty()) return emptyArray()
        
        return paramTypeNames.mapNotNull { typeName ->
            when (val trimmedName = typeName.trim()) {
                "boolean" -> Boolean::class.javaPrimitiveType
                "byte" -> Byte::class.javaPrimitiveType
                "short" -> Short::class.javaPrimitiveType
                "int" -> Int::class.javaPrimitiveType
                "long" -> Long::class.javaPrimitiveType
                "float" -> Float::class.javaPrimitiveType
                "double" -> Double::class.javaPrimitiveType
                "char" -> Char::class.javaPrimitiveType
                "void" -> Void.TYPE
                else -> XposedHelpers.findClassIfExists(trimmedName, classLoader)
            }
        }.toTypedArray()
    }
}
