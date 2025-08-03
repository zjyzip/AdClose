package com.close.hook.ads.preference

import android.content.Context
import android.content.SharedPreferences
import com.close.hook.ads.data.model.CustomHookInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import de.robv.android.xposed.XSharedPreferences

class HookPrefs private constructor(
    private val context: Context?,
    private val isXp: Boolean
) {

    companion object {
        private const val PREF_NAME = "com.close.hook.ads_preferences"
        private const val MODULE_PACKAGE_NAME = "com.close.hook.ads"

        private const val KEY_PREFIX_CUSTOM_HOOK = "custom_hook_configs_"
        private const val KEY_PREFIX_OVERALL_HOOK = "overall_hook_enabled_"
        const val KEY_COLLECT_RESPONSE_BODY = "collect_response_body_enabled"

        private val GSON = Gson()

        @Volatile
        private var sXPrefs: XSharedPreferences? = null

        private fun buildKey(prefix: String, packageName: String?): String {
            return prefix + (if (packageName.isNullOrEmpty()) "global" else packageName)
        }

        fun getXpInstance(): HookPrefs {
            return HookPrefs(null, true).apply {
                initXPrefs()
            }
        }

        fun getInstance(context: Context): HookPrefs {
            return HookPrefs(context, false)
        }

        fun initXPrefs() {
            if (sXPrefs == null) {
                synchronized(this) {
                    if (sXPrefs == null) {
                        sXPrefs = XSharedPreferences(MODULE_PACKAGE_NAME, PREF_NAME).also {
                            @Suppress("SetWorldReadable")
                            it.makeWorldReadable()
                        }
                    }
                }
            }
            sXPrefs?.reload()
        }
    }

    @Suppress("DEPRECATION")
    private val appPrefs: SharedPreferences? = context?.let {
        try {
            it.getSharedPreferences(PREF_NAME, Context.MODE_WORLD_READABLE)
        } catch (e: SecurityException) {
            null
        }
    }

    private fun getActivePrefs(): SharedPreferences? {
        return if (isXp) sXPrefs else appPrefs
    }

    private inline fun editAndApply(action: SharedPreferences.Editor.() -> Unit) {
        if (!isXp) {
            appPrefs?.edit()?.apply(action)?.apply()
        }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return getActivePrefs()?.getBoolean(key, defaultValue) ?: defaultValue
    }

    fun setBoolean(key: String, value: Boolean) {
        editAndApply { putBoolean(key, value) }
    }

    fun getInt(key: String, defaultValue: Int): Int {
        return getActivePrefs()?.getInt(key, defaultValue) ?: defaultValue
    }

    fun setInt(key: String, value: Int) {
        editAndApply { putInt(key, value) }
    }

    fun getLong(key: String, defaultValue: Long): Long {
        return getActivePrefs()?.getLong(key, defaultValue) ?: defaultValue
    }

    fun setLong(key: String, value: Long) {
        editAndApply { putLong(key, value) }
    }

    fun getFloat(key: String, defaultValue: Float): Float {
        return getActivePrefs()?.getFloat(key, defaultValue) ?: defaultValue
    }

    fun setFloat(key: String, value: Float) {
        editAndApply { putFloat(key, value) }
    }

    fun getString(key: String, defaultValue: String): String? {
        return getActivePrefs()?.getString(key, defaultValue) ?: defaultValue
    }

    fun setString(key: String, value: String?) {
        editAndApply { putString(key, value) }
    }

    fun contains(key: String): Boolean {
        return getActivePrefs()?.contains(key) ?: false
    }

    fun remove(key: String) {
        editAndApply { remove(key) }
    }

    fun clear() {
        editAndApply { clear() }
    }

    fun getCustomHookConfigs(packageName: String?): List<CustomHookInfo> {
        val key = buildKey(KEY_PREFIX_CUSTOM_HOOK, packageName)
        val json = getString(key, "[]")
        return try {
            GSON.fromJson(json, object : TypeToken<List<CustomHookInfo>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setCustomHookConfigs(packageName: String?, configs: List<CustomHookInfo>) {
        val key = buildKey(KEY_PREFIX_CUSTOM_HOOK, packageName)
        val json = GSON.toJson(configs)
        setString(key, json)
    }

    fun getOverallHookEnabled(packageName: String?): Boolean {
        val key = buildKey(KEY_PREFIX_OVERALL_HOOK, packageName)
        return getBoolean(key, false)
    }

    fun setOverallHookEnabled(packageName: String?, isEnabled: Boolean) {
        val key = buildKey(KEY_PREFIX_OVERALL_HOOK, packageName)
        setBoolean(key, isEnabled)
    }

    fun getAll(): Map<String, Any?> {
        return getActivePrefs()?.all ?: emptyMap()
    }
}
