package com.close.hook.ads.preference

import android.content.Context
import android.content.SharedPreferences
import com.close.hook.ads.data.model.CustomHookInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import de.robv.android.xposed.XSharedPreferences

class HookPrefs {

    companion object {
        private const val PREF_NAME = "com.close.hook.ads_preferences"
        private const val MODULE_PACKAGE_NAME = "com.close.hook.ads"

        private const val CUSTOM_HOOK_CONFIGS_KEY_PREFIX = "custom_hook_configs_"
        private const val OVERALL_HOOK_ENABLED_KEY_PREFIX = "overall_hook_enabled_"
        private val GSON = Gson()

        fun getOverallHookEnabledKey(packageName: String?): String {
            return if (packageName.isNullOrEmpty()) OVERALL_HOOK_ENABLED_KEY_PREFIX + "global"
                   else OVERALL_HOOK_ENABLED_KEY_PREFIX + packageName
        }
    }

    private val prefs: SharedPreferences?
    private val xPrefs: XSharedPreferences?

    constructor(context: Context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        this.xPrefs = null
    }

    constructor() {
        this.prefs = null
        this.xPrefs = XSharedPreferences(MODULE_PACKAGE_NAME, PREF_NAME).also {
            it.makeWorldReadable()
            it.reload()
        }
    }

    fun reloadXPrefs() {
        xPrefs?.reload()
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefs?.getBoolean(key, defaultValue)
            ?: xPrefs?.getBoolean(key, defaultValue)
            ?: defaultValue
    }

    fun setBoolean(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    fun getInt(key: String, defaultValue: Int): Int {
        return prefs?.getInt(key, defaultValue)
            ?: xPrefs?.getInt(key, defaultValue)
            ?: defaultValue
    }

    fun setInt(key: String, value: Int) {
        prefs?.edit()?.putInt(key, value)?.apply()
    }

    fun getLong(key: String, defaultValue: Long): Long {
        return prefs?.getLong(key, defaultValue)
            ?: xPrefs?.getLong(key, defaultValue)
            ?: defaultValue
    }

    fun setLong(key: String, value: Long) {
        prefs?.edit()?.putLong(key, value)?.apply()
    }

    fun getFloat(key: String, defaultValue: Float): Float {
        return prefs?.getFloat(key, defaultValue)
            ?: xPrefs?.getFloat(key, defaultValue)
            ?: defaultValue
    }

    fun setFloat(key: String, value: Float) {
        prefs?.edit()?.putFloat(key, value)?.apply()
    }

    fun getString(key: String, defaultValue: String): String {
        val result = prefs?.getString(key, null)
        return if (result != null) result
               else xPrefs?.getString(key, defaultValue) ?: defaultValue
    }

    fun setString(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
    }

    fun contains(key: String): Boolean {
        return prefs?.contains(key)
            ?: xPrefs?.contains(key)
            ?: false
    }

    fun remove(key: String) {
        prefs?.edit()?.remove(key)?.apply()
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }

    fun getCustomHookConfigs(packageName: String?): List<CustomHookInfo> {
        val key = if (packageName.isNullOrEmpty()) CUSTOM_HOOK_CONFIGS_KEY_PREFIX + "global" else CUSTOM_HOOK_CONFIGS_KEY_PREFIX + packageName
        val json = getString(key, "[]")
        return try {
            GSON.fromJson(json, object : TypeToken<List<CustomHookInfo>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setCustomHookConfigs(packageName: String?, configs: List<CustomHookInfo>) {
        val key = if (packageName.isNullOrEmpty()) CUSTOM_HOOK_CONFIGS_KEY_PREFIX + "global" else CUSTOM_HOOK_CONFIGS_KEY_PREFIX + packageName
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
