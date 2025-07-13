package com.close.hook.ads.preference

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.provider.HookPrefsProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class HookPrefs(private val context: Context) {

    companion object {
        private val CONTENT_URI: Uri = HookPrefsProvider.CONTENT_URI
        const val PREF_NAME = "com.close.hook.ads_preferences"
        private const val OVERALL_HOOK_ENABLED_KEY_PREFIX = "overall_hook_enabled_"
        const val CUSTOM_HOOK_CONFIGS_KEY_PREFIX = "custom_hook_configs_"
        private val GSON = Gson()

        fun getOverallHookEnabledKey(packageName: String?): String =
            OVERALL_HOOK_ENABLED_KEY_PREFIX + (packageName ?: "global")

        private fun getCustomHookConfigsKey(packageName: String?): String =
            CUSTOM_HOOK_CONFIGS_KEY_PREFIX + (packageName ?: "global")
    }

    private val contentResolver = context.contentResolver

    private fun callProvider(
        method: String,
        extrasBuilder: Bundle.() -> Unit = {}
    ): Bundle? {
        val extras = Bundle().apply(extrasBuilder)
        return contentResolver.call(
            CONTENT_URI,
            method,
            null,
            extras
        )
    }

    private fun <T> get(key: String, default: T, type: String): T {
        val result = callProvider(HookPrefsProvider.METHOD_GET_VALUE) {
            putString(HookPrefsProvider.ARG_KEY, key)
            putString(HookPrefsProvider.ARG_DEFAULT_VALUE, default.toString())
            putString(HookPrefsProvider.ARG_TYPE, type)
        }
        return result?.let { bundle ->
            when (type) {
                "boolean" -> bundle.getBoolean(HookPrefsProvider.RESULT_VALUE, default as Boolean) as T
                "int" -> bundle.getInt(HookPrefsProvider.RESULT_VALUE, default as Int) as T
                "long" -> bundle.getLong(HookPrefsProvider.RESULT_VALUE, default as Long) as T
                "float" -> bundle.getFloat(HookPrefsProvider.RESULT_VALUE, default as Float) as T
                "string" -> bundle.getString(HookPrefsProvider.RESULT_VALUE, default as String) as T
                else -> default
            }
        } ?: default
    }

    private fun <T> set(key: String, value: T, type: String) {
        callProvider(HookPrefsProvider.METHOD_SET_VALUE) {
            putString(HookPrefsProvider.ARG_KEY, key)
            when (value) {
                is Boolean -> { putBoolean(HookPrefsProvider.ARG_VALUE, value) }
                is Int -> { putInt(HookPrefsProvider.ARG_VALUE, value) }
                is Long -> { putLong(HookPrefsProvider.ARG_VALUE, value) }
                is Float -> { putFloat(HookPrefsProvider.ARG_VALUE, value) }
                is String -> { putString(HookPrefsProvider.ARG_VALUE, value) }
            }
            putString(HookPrefsProvider.ARG_TYPE, type)
        }
    }

    fun getBoolean(key: String, default: Boolean) = get(key, default, "boolean")
    fun setBoolean(key: String, value: Boolean) = set(key, value, "boolean")

    fun getInt(key: String, default: Int) = get(key, default, "int")
    fun setInt(key: String, value: Int) = set(key, value, "int")

    fun getLong(key: String, default: Long) = get(key, default, "long")
    fun setLong(key: String, value: Long) = set(key, value, "long")

    fun getFloat(key: String, default: Float) = get(key, default, "float")
    fun setFloat(key: String, value: Float) = set(key, value, "float")

    fun getString(key: String, default: String) = get(key, default, "string")
    fun setString(key: String, value: String) = set(key, value, "string")

    fun contains(key: String): Boolean =
        callProvider(HookPrefsProvider.METHOD_CONTAINS_KEY) {
            putString(HookPrefsProvider.ARG_KEY, key)
        }?.getBoolean(HookPrefsProvider.RESULT_BOOLEAN, false) ?: false

    fun remove(key: String) {
        callProvider(HookPrefsProvider.METHOD_REMOVE_KEY) {
            putString(HookPrefsProvider.ARG_KEY, key)
        }
    }

    fun clear() {
        callProvider(HookPrefsProvider.METHOD_CLEAR_ALL)
    }

    fun getAll(): Map<String, Any> {
        val resultBundle = callProvider(HookPrefsProvider.METHOD_GET_ALL)
        val allPrefs = mutableMapOf<String, Any>()
        resultBundle?.getBundle(HookPrefsProvider.RESULT_VALUE)?.keySet()?.forEach { key ->
            resultBundle.getBundle(HookPrefsProvider.RESULT_VALUE)?.get(key)?.let { value ->
                allPrefs[key] = value
            }
        }
        return allPrefs
    }

    fun getCustomHookConfigs(packageName: String?): List<CustomHookInfo> {
        val result = callProvider(HookPrefsProvider.METHOD_GET_CUSTOM_HOOK_CONFIGS) {
            putString(HookPrefsProvider.ARG_PACKAGE_NAME, packageName)
        }
        val configsJson = result?.getString(HookPrefsProvider.RESULT_VALUE) ?: "[]"
        return try {
            GSON.fromJson(configsJson, object : TypeToken<List<CustomHookInfo>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setCustomHookConfigs(packageName: String?, configs: List<CustomHookInfo>) {
        val configsJson = GSON.toJson(configs)
        callProvider(HookPrefsProvider.METHOD_SET_CUSTOM_HOOK_CONFIGS) {
            putString(HookPrefsProvider.ARG_PACKAGE_NAME, packageName)
            putString(HookPrefsProvider.ARG_CONFIGS_JSON, configsJson)
        }
    }

    fun getOverallHookEnabled(packageName: String?): Boolean {
        val key = getOverallHookEnabledKey(packageName)
        return getBoolean(key, false)
    }

    fun setOverallHookEnabled(packageName: String?, isEnabled: Boolean) {
        val key = getOverallHookEnabledKey(packageName)
        setBoolean(key, isEnabled)
    }
}
