package com.close.hook.ads.hook.shield

import android.content.Context
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.model.HookMethodType
import com.close.hook.ads.data.model.LogEntry
import com.close.hook.ads.hook.util.ContextUtil
import com.close.hook.ads.hook.util.HookUtil
import com.close.hook.ads.hook.util.HookValueParser
import com.close.hook.ads.hook.util.LogProxy
import com.close.hook.ads.hook.util.StringFinderKit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.Arrays

object CustomHook {
    private const val TAG = "CustomHook"

    private fun log(config: CustomHookInfo, message: String, stackTrace: String? = null) {
        LogProxy.log(TAG, message, stackTrace, config.packageName ?: LogEntry.GLOBAL_HOOK_SCOPE)
    }

    fun hookCustomAds(
        classLoader: ClassLoader,
        customConfigs: List<CustomHookInfo>,
        isScopeEnabled: Boolean,
        packageName: String?
    ) {
        if (!isScopeEnabled) return
        customConfigs.forEach { storedConfig ->
            if (!storedConfig.isEnabled) return@forEach
            val config = storedConfig.copy(packageName = packageName)
            try {
                applyHookConfig(config, classLoader)
            } catch (error: Throwable) {
                log(config, "Error applying config for ${config.className}: $error")
            }
        }
    }

    private fun applyHookConfig(config: CustomHookInfo, classLoader: ClassLoader) {
        when (config.hookMethodType) {
            HookMethodType.HOOK_MULTIPLE_METHODS -> {
                val names = config.methodNames.orEmpty().toSet()
                if (names.isEmpty()) return
                XposedHelpers.findClass(config.className, classLoader).declaredMethods
                    .filter { it.name in names }
                    .forEach { installConstantResult(it, config, "before") }
            }
            HookMethodType.FIND_AND_HOOK_METHOD -> installExactMethod(config, classLoader)
            HookMethodType.HOOK_ALL_METHODS -> {
                val name = config.methodNames?.firstOrNull() ?: return
                XposedHelpers.findClass(config.className, classLoader).declaredMethods
                    .filter { it.name == name }
                    .forEach { method ->
                        try {
                            val action = configuredAction(method, config)
                            HookUtil.hookMethod(method, config.hookPoint) { param -> action(param) }
                        } catch (error: Throwable) {
                            log(config, "Cannot apply config to $method: $error")
                        }
                    }
            }
            HookMethodType.SET_STATIC_OBJECT_FIELD -> {
                val name = config.fieldName?.takeIf { it.isNotBlank() } ?: return
                val clazz = XposedHelpers.findClass(config.className, classLoader)
                val field = XposedHelpers.findField(clazz, name)
                val value = HookValueParser.coerce(config.fieldValue, field.type)
                HookUtil.setStaticObjectField(clazz, name, value)
                log(config, "setStaticObjectField - Class=${config.className}, Field=$name, Value=$value")
            }
            HookMethodType.HOOK_METHODS_BY_STRING_MATCH -> {
                val strings = config.searchStrings?.takeIf { it.isNotEmpty() } ?: return
                SDKAdsKit.hookMethodsByStringMatch(
                    "custom:strings:${config.packageName ?: LogEntry.GLOBAL_HOOK_SCOPE}:${config.id}", strings
                ) {
                    installConstantResult(it, config, config.hookPoint)
                }
            }
            HookMethodType.FIND_METHODS_WITH_STRING -> {
                val search = config.searchStrings?.firstOrNull() ?: return
                val name = config.methodNames?.firstOrNull() ?: return
                StringFinderKit.findMethodsWithString(
                    "custom:named:${config.packageName ?: LogEntry.GLOBAL_HOOK_SCOPE}:${config.id}", search, name, classLoader
                ).forEach { installConstantResult(it, config, config.hookPoint) }
            }
            HookMethodType.REPLACE_CONTEXT_WITH_FAKE -> installExactMethod(config, classLoader) { param ->
                handleReplaceContextWithFake(param, config)
            }
            HookMethodType.LOG_METHOD_PARAMS -> installExactMethod(config, classLoader) { param ->
                val args = param.args?.let(Arrays::toString) ?: "null"
                log(config, "LOG_PARAMS: ${config.className}.${config.methodNames?.firstOrNull()} Args: $args",
                    HookUtil.getFormattedStackTrace())
            }
            HookMethodType.LOG_METHOD_RETURN_VALUE -> installExactMethod(config, classLoader, "after") { param ->
                log(config, "LOG_RETURN: ${config.className}.${config.methodNames?.firstOrNull()} Result: ${param.result}",
                    HookUtil.getFormattedStackTrace())
            }
        }
    }

    private fun installExactMethod(
        config: CustomHookInfo,
        classLoader: ClassLoader,
        point: String? = config.hookPoint,
        callback: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
    ) {
        val name = config.methodNames?.firstOrNull() ?: return
        val types = HookValueParser.parameterTypes(config.parameterTypes, classLoader)
        val method = XposedHelpers.findClass(config.className, classLoader).getDeclaredMethod(name, *types)
        val action = callback ?: configuredAction(method, config)
        HookUtil.hookMethod(method, point) { param -> action(param) }
    }

    private fun installConstantResult(method: Method, config: CustomHookInfo, point: String?) {
        try {
            val value = HookValueParser.coerce(config.returnValue, method.returnType)
            HookUtil.hookMethod(method, point) { param ->
                param.result = value
                log(config, "Set return value for $method", HookUtil.getFormattedStackTrace())
            }
        } catch (error: Throwable) {
            // An incompatible overload must not stop compatible methods in the same scan.
            log(config, "Cannot apply config to $method: $error")
        }
    }

    private fun configuredAction(method: Method, config: CustomHookInfo): (XC_MethodHook.MethodHookParam) -> Unit {
        val fullName = "${method.declaringClass.name}.${method.name}"
        if (!config.parameterReplacements.isNullOrEmpty()) {
            val types = method.parameterTypes
            // Validate once before installation; callbacks only assign pre-converted compatible values.
            val replacements = config.parameterReplacements.map { (index, literal) ->
                require(index in types.indices) { "Parameter index out of range: $index for $fullName" }
                index to HookValueParser.coerce(literal, types[index])
            }
            return { param ->
                replacements.forEach { (index, value) -> param.args[index] = value }
                log(config, "Replaced parameters for $fullName", HookUtil.getFormattedStackTrace())
            }
        }
        if (config.returnValue != null) {
            val value = HookValueParser.coerce(config.returnValue, method.returnType)
            return { param ->
                param.result = value
                log(config, "Set return value to '$value' for $fullName", HookUtil.getFormattedStackTrace())
            }
        }
        return { param -> handleAutomaticHook(param, config) }
    }

    private fun handleAutomaticHook(param: XC_MethodHook.MethodHookParam, config: CustomHookInfo) {
        val method = param.method as? Method ?: return
        val fullName = "${method.declaringClass.name}.${method.name}"
        if (method.returnType == method.declaringClass) {
            if (method.parameterTypes.any { it.isInterface }) {
                param.result = null
                log(config, "Builder method has interface parameter, returned null: $fullName")
            } else if (param.thisObject != null) {
                param.result = param.thisObject
                log(config, "Skipped builder body while preserving receiver: $fullName")
            }
            return
        }
        val args = param.args ?: return
        method.parameterTypes.forEachIndexed { index, type ->
            if (index in args.indices && type.isPrimitive) args[index] = HookValueParser.defaultValue(type)
        }
    }

    private fun handleReplaceContextWithFake(param: XC_MethodHook.MethodHookParam, config: CustomHookInfo) {
        val realContext = ContextUtil.applicationContext ?: return
        val method = param.method as? Method ?: return
        val fake = SmartFakeContext(realContext, config.packageName ?: LogEntry.GLOBAL_HOOK_SCOPE)
        if (Context::class.java.isAssignableFrom(method.returnType) && method.returnType.isInstance(fake)) {
            param.result = fake
            log(config, "Replaced Context return value for $method", HookUtil.getFormattedStackTrace())
        }
        param.args?.forEachIndexed { index, argument ->
            if (argument is Context && method.parameterTypes.getOrNull(index)?.isInstance(fake) == true) {
                param.args[index] = fake
                log(config, "Replaced Context argument $index for $method", HookUtil.getFormattedStackTrace())
            }
        }
    }
}
