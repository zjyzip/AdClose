package com.close.hook.ads.hook.ha

import android.content.Context
import android.content.ContextWrapper
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

        customConfigs.filter { it.isEnabled }.forEach { config ->
            try {
                applyHookConfig(config, classLoader)
            } catch (e: Throwable) {
                LogProxy.log(TAG, "Error applying config for ${config.className}: ${e.message}")
            }
        }
    }

    private fun applyHookConfig(config: CustomHookInfo, classLoader: ClassLoader) {
        val parsedReturnValue = parseReturnValue(config.returnValue)
        when (config.hookMethodType) {
            HookMethodType.HOOK_MULTIPLE_METHODS -> handleHookMultipleMethods(config, classLoader, parsedReturnValue)
            HookMethodType.FIND_AND_HOOK_METHOD -> handleFindAndHookMethod(config, classLoader, parsedReturnValue)
            HookMethodType.HOOK_ALL_METHODS -> handleHookAllMethods(config, classLoader, parsedReturnValue)
            HookMethodType.SET_STATIC_OBJECT_FIELD -> handleSetStaticField(config, classLoader)
            HookMethodType.HOOK_METHODS_BY_STRING_MATCH -> handleHookByStringMatch(config, parsedReturnValue)
            HookMethodType.FIND_METHODS_WITH_STRING -> handleFindMethodWithString(config, classLoader, parsedReturnValue)
            HookMethodType.REPLACE_CONTEXT_WITH_FAKE -> handleReplaceContext(config, classLoader)
        }
    }

    private fun handleHookMultipleMethods(config: CustomHookInfo, classLoader: ClassLoader, returnValue: Any?) {
        config.methodNames?.takeIf { it.isNotEmpty() }?.let {
            HookUtil.hookMultipleMethods(classLoader, config.className, it.toTypedArray(), returnValue)
            LogProxy.log(TAG, "hookMultipleMethods - Class=${config.className}, Methods=${it}, Return=$returnValue")
        }
    }

    private fun handleFindAndHookMethod(config: CustomHookInfo, classLoader: ClassLoader, returnValue: Any?) {
        config.methodNames?.firstOrNull()?.let { methodName ->
            val paramTypes = resolveParameterTypes(config.parameterTypes, classLoader)
            HookUtil.findAndHookMethod(config.className, methodName, paramTypes, config.hookPoint, { param ->
                handleHookedMethod(param, config, returnValue)
            }, classLoader)
        }
    }

    private fun handleHookAllMethods(config: CustomHookInfo, classLoader: ClassLoader, returnValue: Any?) {
        config.methodNames?.firstOrNull()?.let { methodName ->
            HookUtil.hookAllMethods(config.className, methodName, config.hookPoint, { param ->
                handleHookedMethod(param, config, returnValue)
                LogProxy.log(TAG, "hookAllMethods - Class=${config.className}, Method=$methodName", HookUtil.getFormattedStackTrace())
            }, classLoader)
        }
    }

    private fun handleSetStaticField(config: CustomHookInfo, classLoader: ClassLoader) {
        config.fieldName?.takeIf { it.isNotEmpty() }?.let { fieldName ->
            val fieldValue = parseReturnValue(config.fieldValue)
            HookUtil.setStaticObjectField(config.className, classLoader, fieldName, fieldValue)
            LogProxy.log(TAG, "setStaticObjectField - Class=${config.className}, Field=$fieldName, Value=$fieldValue")
        }
    }

    private fun handleHookByStringMatch(config: CustomHookInfo, returnValue: Any?) {
        config.searchStrings?.takeIf { it.isNotEmpty() }?.let { strings ->
            SDKAdsKit.hookMethodsByStringMatch(config.id, strings) { method ->
                HookUtil.hookMethod(method, config.hookPoint) { param ->
                    param.result = returnValue
                    LogProxy.log(TAG, "hookMethodsByStringMatch - ID=${config.id}, Method=$method", HookUtil.getFormattedStackTrace())
                }
            }
        }
    }

    private fun handleFindMethodWithString(config: CustomHookInfo, classLoader: ClassLoader, returnValue: Any?) {
        val searchString = config.searchStrings?.firstOrNull()
        val methodName = config.methodNames?.firstOrNull()
        if (searchString != null && methodName != null) {
            StringFinderKit.findMethodsWithString(config.id, searchString, methodName)?.forEach { methodData ->
                methodData.getMethodInstance(classLoader)?.let { method ->
                    HookUtil.hookMethod(method, config.hookPoint) { param ->
                        param.result = returnValue
                        LogProxy.log(TAG, "findMethodsWithString - ID=${config.id}, Found=$method", HookUtil.getFormattedStackTrace())
                    }
                }
            }
        }
    }

    private fun handleReplaceContext(config: CustomHookInfo, classLoader: ClassLoader) {
        config.methodNames?.firstOrNull()?.let { methodName ->
            val paramTypes = resolveParameterTypes(config.parameterTypes, classLoader)
            HookUtil.findAndHookMethod(config.className, methodName, paramTypes, config.hookPoint, { param ->
                handleReplaceContextWithFake(param, config)
            }, classLoader)
        }
    }

    private fun handleHookedMethod(param: XC_MethodHook.MethodHookParam, config: CustomHookInfo, parsedReturnValue: Any?) {
        val method = param.method as? Method
        val paramClasses = method?.parameterTypes ?: emptyArray()
        val fullMethodName = "${config.className}.${config.methodNames?.firstOrNull()}"

        when {
            !config.returnValue.isNullOrBlank() -> {
                param.result = parsedReturnValue
                val message = "Set return value to '$parsedReturnValue' for method: $fullMethodName (based on config)"
                LogProxy.log(TAG, message, HookUtil.getFormattedStackTrace())
            }
            method?.returnType?.let { it == method.declaringClass } == true -> {
                val hasInterfaceParam = paramClasses.any { it.isInterface }
                if (hasInterfaceParam) {
                    param.result = null
                    val message = "Builder method has interface param, returned null to interrupt chain: $fullMethodName"
                    LogProxy.log(TAG, message, HookUtil.getFormattedStackTrace())
                } else {
                    val message = "Builder method has no interface param, skip original method body: $fullMethodName"
                    LogProxy.log(TAG, message, HookUtil.getFormattedStackTrace())
                }
            }
            else -> {
                param.args.forEachIndexed { i, arg ->
                    if (arg != null && i < paramClasses.size) {
                        val replacedValue: Pair<Any?, String?> = when {
                            Boolean::class.java.isAssignableFrom(paramClasses[i]) -> false to "Boolean"
                            Int::class.java.isAssignableFrom(paramClasses[i]) -> 0 to "Int"
                            Long::class.java.isAssignableFrom(paramClasses[i]) -> 0L to "Long"
                            Float::class.java.isAssignableFrom(paramClasses[i]) -> 0.0f to "Float"
                            Double::class.java.isAssignableFrom(paramClasses[i]) -> 0.0 to "Double"
                            else -> null to null
                        }
                        if (replacedValue.second != null) {
                            param.args[i] = replacedValue.first
                            val message = "Replaced ${replacedValue.second} parameter at index $i with '${replacedValue.first}' for method: $fullMethodName"
                            LogProxy.log(TAG, message, HookUtil.getFormattedStackTrace())
                        }
                    }
                }
            }
        }
    }

    private fun handleReplaceContextWithFake(param: XC_MethodHook.MethodHookParam, config: CustomHookInfo) {
        val realAppContext = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication"
        ) as? Context
        
        realAppContext ?: return

        val fakeContext = SmartFakeContext(realAppContext) // Stable, 稳定
        // val fakeContext = createFakeApplicationContext(realAppContext) // Thorough 简易通彻不稳定

        val method = param.method as Method
        val fullMethodName = "${config.className}.${config.methodNames?.firstOrNull()}"

        if (Context::class.java.isAssignableFrom(method.returnType)) {
            param.result = fakeContext
            val message = "Replaced Context return value with FakeContext for method: $fullMethodName"
            LogProxy.log(TAG, message, HookUtil.getFormattedStackTrace())
        }

        param.args.forEachIndexed { i, arg ->
            if (arg is Context) {
                param.args[i] = fakeContext
                val message = "Replaced Context parameter at index $i with FakeContext for method: $fullMethodName"
                LogProxy.log(TAG, message, HookUtil.getFormattedStackTrace())
            }
        }
    }

    private fun createFakeContext(realAppContext: Context): Context {
        return object : ContextWrapper(realAppContext) {
            override fun getApplicationContext(): Context {
                return this
            }
        }
    }

    private fun parseReturnValue(valueStr: String?): Any? {
        val trimmed = valueStr?.trim() ?: return null
        return when {
            trimmed.equals("true", ignoreCase = true) -> true
            trimmed.equals("false", ignoreCase = true) -> false
            trimmed.equals("null", ignoreCase = true) -> null
            else -> trimmed.toIntOrNull()
                ?: trimmed.toLongOrNull()
                ?: trimmed.toFloatOrNull()
                ?: trimmed.toDoubleOrNull()
                ?: trimmed
        }
    }

    private fun resolveParameterTypes(paramTypeNames: List<String>?, classLoader: ClassLoader): Array<Any> {
        return paramTypeNames?.mapNotNull { typeName ->
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
        }?.toTypedArray() ?: emptyArray()
    }
}
