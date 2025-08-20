package com.close.hook.ads.preference

import android.content.Context
import android.util.Log
import android.content.SharedPreferences
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.hook.HookLogic
import com.close.hook.ads.service.ServiceManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class HookPrefs private constructor(
    private val prefs: SharedPreferences?
) {

    companion object {
        private const val PREF_NAME = "com.close.hook.ads_preferences"
        private val GSON = Gson()

        private const val KEY_PREFIX_CUSTOM_HOOK = "custom_hook_configs_"
        private const val KEY_PREFIX_OVERALL_HOOK = "overall_hook_enabled_"
        private const val KEY_PREFIX_DETECTED_HOOK = "detected_hook_configs_"
        private const val KEY_PREFIX_ENABLE_LOGGING = "enable_logging_"
        const val KEY_COLLECT_RESPONSE_BODY = "collect_response_body_enabled"

        private fun buildKey(prefix: String, packageName: String?): String {
            return prefix + (if (packageName.isNullOrEmpty()) "global" else packageName)
        }

        /**
         * 供模块 UI 进程调用，获取一个可读写的配置实例。
         *
         * 内部通过 `ServiceManager` 连接，
         * 最终调用 XposedService.getRemotePreferences()。
         */
        @JvmStatic
        fun getInstance(context: Context): HookPrefs {
            val remotePrefs = try {
                ServiceManager.service?.getRemotePreferences(PREF_NAME)
            } catch (e: Exception) {
                Log.w("HookPrefs", "Failed to get RemotePreferences, falling back to local. Error: $e")
                null
            }
            return HookPrefs(remotePrefs)
        }

        /**
         * 供被 Hook 进程调用，获取一个只读的配置实例。
         *
         * 依赖 `LibXposedEntry` 中初始化的 `HookLogic.xposedInterface`，
         * 对应 XposedInterface.getRemotePreferences()。
         */
        @JvmStatic
        fun getXpInstance(): HookPrefs {
            val remotePrefs = try {
                // 从 HookLogic持有的 XposedInterface 获取配置
                HookLogic.xposedInterface?.getRemotePreferences(PREF_NAME)
            } catch (e: Exception) {
                Log.e("HookPrefs", "Failed to get RemotePreferences in Xposed context. Error: $e")
                null
            }
            Log.d("HookPrefs", "Xposed instance uses: ${remotePrefs?.javaClass?.simpleName}")
            return HookPrefs(remotePrefs)
        }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefs?.getBoolean(key, defaultValue) ?: defaultValue
    }

    fun setBoolean(key: String, value: Boolean) {
        (prefs as? android.content.SharedPreferences)?.edit()?.putBoolean(key, value)?.apply()
    }

    fun getInt(key: String, defaultValue: Int): Int {
        return prefs?.getInt(key, defaultValue) ?: defaultValue
    }

    fun setInt(key: String, value: Int) {
        (prefs as? android.content.SharedPreferences)?.edit()?.putInt(key, value)?.apply()
    }

    fun getLong(key: String, defaultValue: Long): Long {
        return prefs?.getLong(key, defaultValue) ?: defaultValue
    }

    fun setLong(key: String, value: Long) {
        (prefs as? android.content.SharedPreferences)?.edit()?.putLong(key, value)?.apply()
    }

    fun getFloat(key: String, defaultValue: Float): Float {
        return prefs?.getFloat(key, defaultValue) ?: defaultValue
    }

    fun setFloat(key: String, value: Float) {
        (prefs as? android.content.SharedPreferences)?.edit()?.putFloat(key, value)?.apply()
    }

    fun getString(key: String, defaultValue: String?): String? {
        return prefs?.getString(key, defaultValue) ?: defaultValue
    }

    fun setString(key: String, value: String?) {
        (prefs as? android.content.SharedPreferences)?.edit()?.putString(key, value)?.apply()
    }

    fun contains(key: String): Boolean {
        return prefs?.contains(key) ?: false
    }

    fun remove(key: String) {
        (prefs as? android.content.SharedPreferences)?.edit()?.remove(key)?.apply()
    }

    fun clear() {
        (prefs as? android.content.SharedPreferences)?.edit()?.clear()?.apply()
    }

    fun getAll(): Map<String, *> {
        return prefs?.all ?: emptyMap<String, Any>()
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
    
    fun getEnableLogging(packageName: String?): Boolean {
        val key = buildKey(KEY_PREFIX_ENABLE_LOGGING, packageName)
        return getBoolean(key, false)
    }

    fun setEnableLogging(packageName: String?, isEnabled: Boolean) {
        val key = buildKey(KEY_PREFIX_ENABLE_LOGGING, packageName)
        setBoolean(key, isEnabled)
    }

    fun getDetectedHooks(packageName: String?): List<CustomHookInfo> {
        val key = buildKey(KEY_PREFIX_DETECTED_HOOK, packageName)
        val json = getString(key, "[]")
        return try {
            GSON.fromJson(json, object : TypeToken<List<CustomHookInfo>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setDetectedHooks(packageName: String?, configs: List<CustomHookInfo>) {
        val key = buildKey(KEY_PREFIX_DETECTED_HOOK, packageName)
        val json = GSON.toJson(configs)
        setString(key, json)
    }
}
