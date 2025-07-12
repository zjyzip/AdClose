package com.close.hook.ads.preference

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.close.hook.ads.provider.HookPrefsProvider
import com.close.hook.ads.data.model.CustomHookInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class HookPrefs(private val context: Context) {

    companion object {
        private const val AUTHORITY = HookPrefsProvider.AUTHORITY
        private val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/com.close.hook.ads_preferences")
        private const val OVERALL_HOOK_ENABLED_KEY_PREFIX = "overall_hook_enabled_"
        private const val CUSTOM_HOOK_CONFIGS_KEY_PREFIX = "custom_hook_configs_"
        private val GSON = Gson()

        fun getOverallHookEnabledKey(packageName: String?): String {
            return if (packageName.isNullOrEmpty()) OVERALL_HOOK_ENABLED_KEY_PREFIX + "global"
                   else OVERALL_HOOK_ENABLED_KEY_PREFIX + packageName
        }

        private fun getCustomHookConfigsKey(packageName: String?): String {
            return if (packageName.isNullOrEmpty()) CUSTOM_HOOK_CONFIGS_KEY_PREFIX + "global"
            else CUSTOM_HOOK_CONFIGS_KEY_PREFIX + packageName
        }
    }

    private val contentResolver = context.contentResolver

    private fun <T> get(key: String, default: T, type: String): T {
        val cursor = contentResolver.query(
            CONTENT_URI,
            arrayOf("key", "value", "type"),
            null,
            arrayOf(key, default.toString(), type),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val value = it.getString(it.getColumnIndexOrThrow("value"))
                @Suppress("UNCHECKED_CAST")
                return when (type) {
                    "boolean" -> value?.toBooleanStrictOrNull() ?: default as Boolean
                    "int" -> value?.toIntOrNull() ?: default as Int
                    "long" -> value?.toLongOrNull() ?: default as Long
                    "float" -> value?.toFloatOrNull() ?: default as Float
                    "string" -> value as? T ?: default
                    else -> default
                } as T
            }
        }
        return default
    }

    private fun <T> set(key: String, value: T) {
        val values = ContentValues().apply {
            when (value) {
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Float -> put(key, value)
                is String -> put(key, value)
            }
        }
        contentResolver.update(CONTENT_URI, values, null, null)
    }

    fun getBoolean(key: String, default: Boolean) = get(key, default, "boolean")
    fun setBoolean(key: String, value: Boolean) = set(key, value)

    fun getInt(key: String, default: Int) = get(key, default, "int")
    fun setInt(key: String, value: Int) = set(key, value)

    fun getLong(key: String, default: Long) = get(key, default, "long")
    fun setLong(key: String, value: Long) = set(key, value)

    fun getFloat(key: String, default: Float) = get(key, default, "float")
    fun setFloat(key: String, value: Float) = set(key, value)

    fun getString(key: String, default: String) = get(key, default, "string")
    fun setString(key: String, value: String) = set(key, value)

    fun contains(key: String): Boolean {
        val cursor = contentResolver.query(
            CONTENT_URI,
            arrayOf("key"),
            null,
            arrayOf(key, "", "string"),
            null
        )
        val result = cursor?.use { it.moveToFirst() } ?: false
        return result
    }

    fun remove(key: String) {
        contentResolver.delete(CONTENT_URI, null, arrayOf(key))
    }

    fun clear() {
        contentResolver.delete(CONTENT_URI, "clear", null)
    }

    fun getCustomHookConfigs(packageName: String?): List<CustomHookInfo> {
        val key = getCustomHookConfigsKey(packageName)
        val json = getString(key, "[]")
        return try {
            GSON.fromJson(json, object : TypeToken<List<CustomHookInfo>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setCustomHookConfigs(packageName: String?, configs: List<CustomHookInfo>) {
        val key = getCustomHookConfigsKey(packageName)
        val json = GSON.toJson(configs)
        setString(key, json)
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
