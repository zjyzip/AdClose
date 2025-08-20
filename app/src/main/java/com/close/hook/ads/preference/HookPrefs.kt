package com.close.hook.ads.preference

import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.util.Log
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.hook.HookLogic
import com.close.hook.ads.service.ServiceManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.FileNotFoundException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class HookPrefs private constructor(
    internal val prefs: SharedPreferences?
) {
    private val configCache = ConcurrentHashMap<String, List<CustomHookInfo>>()
    private val versionCache = ConcurrentHashMap<String, Long>()

    companion object {
        private const val PREF_NAME = "com.close.hook.ads_preferences"
        private val GSON = Gson()

        internal const val KEY_PREFIX_CUSTOM_HOOK_OLD = "custom_hook_configs_"
        private const val KEY_PREFIX_CUSTOM_HOOK_VERSION = "custom_hook_version_"
        private const val KEY_PREFIX_OVERALL_HOOK = "overall_hook_enabled_"
        private const val KEY_PREFIX_DETECTED_HOOK = "detected_hook_configs_"
        private const val KEY_PREFIX_ENABLE_LOGGING = "enable_logging_"
        const val KEY_COLLECT_RESPONSE_BODY = "collect_response_body_enabled"

        private const val FILE_PREFIX_CUSTOM_HOOK = "custom_hooks_"
        private const val FILE_PREFIX_DETECTED_HOOK = "detected_hooks_"

        internal fun buildKey(prefix: String, packageName: String?): String {
            return prefix + (packageName ?: "global")
        }

        private fun buildFileName(prefix: String, packageName: String?): String {
            return prefix + (packageName ?: "global") + ".json"
        }

        /**
         * 供模块 UI 进程调用，获取一个可读写的配置实例。
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
         */
        @JvmStatic
        fun getXpInstance(): HookPrefs {
            val remotePrefs = try {
                HookLogic.xposedInterface?.getRemotePreferences(PREF_NAME)
            } catch (e: Exception) {
                Log.e("HookPrefs", "Failed to get RemotePreferences in Xposed context. Error: $e")
                null
            }
            Log.d("HookPrefs", "Xposed instance uses: ${remotePrefs?.javaClass?.simpleName}")
            return HookPrefs(remotePrefs)
        }
    }

    private inline fun editAndApply(crossinline block: SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.apply(block)?.apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean = prefs?.getBoolean(key, defaultValue) ?: defaultValue
    fun getInt(key: String, defaultValue: Int): Int = prefs?.getInt(key, defaultValue) ?: defaultValue
    fun getLong(key: String, defaultValue: Long): Long = prefs?.getLong(key, defaultValue) ?: defaultValue
    fun getFloat(key: String, defaultValue: Float): Float = prefs?.getFloat(key, defaultValue) ?: defaultValue
    fun getString(key: String, defaultValue: String?): String? = prefs?.getString(key, defaultValue) ?: defaultValue

    fun setBoolean(key: String, value: Boolean) = editAndApply { putBoolean(key, value) }
    fun setInt(key: String, value: Int) = editAndApply { putInt(key, value) }
    fun setLong(key: String, value: Long) = editAndApply { putLong(key, value) }
    fun setFloat(key: String, value: Float) = editAndApply { putFloat(key, value) }
    fun setString(key: String, value: String?) = editAndApply { putString(key, value) }
    fun remove(key: String) = editAndApply { remove(key) }
    fun clear() = editAndApply { clear() }

    fun contains(key: String): Boolean = prefs?.contains(key) ?: false
    fun getAll(): Map<String, *> = prefs?.all ?: emptyMap<String, Any>()

    /**
     * 根据当前运行上下文（UI进程或Hook进程），调用相应的服务接口来打开远程文件。
     * @param fileName 要打开的文件名。
     */
    private fun openRemoteFileForCurrentContext(fileName: String): ParcelFileDescriptor? {
        return try {
            if (ServiceManager.service != null) {
                ServiceManager.service?.openRemoteFile(fileName)
            } else {
                HookLogic.xposedInterface?.openRemoteFile(fileName)
            }
        } catch (e: Exception) {
            Log.e("HookPrefs", "Error opening remote file '$fileName'", e)
            null
        }
    }

    fun getCustomHookConfigs(packageName: String?): List<CustomHookInfo> {
        val versionKey = buildKey(KEY_PREFIX_CUSTOM_HOOK_VERSION, packageName)
        val currentVersion = getLong(versionKey, 0L)
        val cachedVersion = versionCache[versionKey]

        if (currentVersion != 0L && currentVersion == cachedVersion && configCache.containsKey(versionKey)) {
            return configCache[versionKey] ?: emptyList()
        }

        val fileName = buildFileName(FILE_PREFIX_CUSTOM_HOOK, packageName)
        val configs = readConfigsFromFile(fileName)

        configCache[versionKey] = configs
        versionCache[versionKey] = currentVersion
        
        return configs
    }

    fun setCustomHookConfigs(packageName: String?, configs: List<CustomHookInfo>) {
        val fileName = buildFileName(FILE_PREFIX_CUSTOM_HOOK, packageName)
        val success = writeConfigsToFile(fileName, configs)
        if (success) {
            val versionKey = buildKey(KEY_PREFIX_CUSTOM_HOOK_VERSION, packageName)
            setLong(versionKey, System.currentTimeMillis())
            Log.d("HookPrefs", "Successfully saved hooks to $fileName and updated version.")
        }
    }

    /**
     * 从远程文件中读取并反序列化Hook配置列表。
     * @param fileName 要读取的文件名。
     */
    private fun readConfigsFromFile(fileName: String): List<CustomHookInfo> {
        return try {
            openRemoteFileForCurrentContext(fileName)?.use { pfd ->
                InputStreamReader(FileInputStream(pfd.fileDescriptor), StandardCharsets.UTF_8).use { reader ->
                    GSON.fromJson(reader, object : TypeToken<List<CustomHookInfo>>() {}.type) ?: emptyList()
                }
            } ?: emptyList()
        } catch (e: Exception) {
            if (e !is FileNotFoundException) {
                Log.e("HookPrefs", "Failed to read configs from file: $fileName", e)
            }
            emptyList()
        }
    }

    /**
     * 将配置列表序列化为JSON并写入到远程文件中。
     * 此操作在UI进程中执行。
     * @param fileName 要写入的文件名。
     * @param configs 要序列化的配置对象。
     */
    private fun writeConfigsToFile(fileName: String, configs: Any): Boolean {
        return try {
            val service = ServiceManager.service
            if (service == null) {
                Log.e("HookPrefs", "Cannot write configs: ServiceManager is not available (not in UI process).")
                return false
            }
            service.openRemoteFile(fileName).use { pfd ->
                OutputStreamWriter(FileOutputStream(pfd.fileDescriptor), StandardCharsets.UTF_8).use { writer ->
                    GSON.toJson(configs, writer)
                }
            }
            true
        } catch (e: Exception) {
            Log.e("HookPrefs", "Failed to save configs to file: $fileName", e)
            false
        }
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
