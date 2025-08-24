package com.close.hook.ads.preference

import android.os.ParcelFileDescriptor
import android.util.Log
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.hook.HookLogic
import com.close.hook.ads.manager.ServiceManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileNotFoundException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

object HookPrefs {

    private const val TAG = "HookPrefs"
    private const val GLOBAL_KEY = "global"

    private const val FILE_GENERAL_SETTINGS = "com.close.hook.ads_preferences.json"
    private const val FILE_PREFIX_CUSTOM_HOOK = "custom_hooks_"

    private const val KEY_PREFIX_OVERALL_HOOK = "overall_hook_enabled_"
    private const val KEY_PREFIX_DETECTED_HOOK = "detected_hook_configs_"
    private const val KEY_PREFIX_ENABLE_LOGGING = "enable_logging_"
    const val KEY_COLLECT_RESPONSE_BODY = "collect_response_body_enabled"

    private val GSON by lazy { Gson() }
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settingsWriteLock = Mutex()

    private interface IFileAccessor {
        fun openRemoteFile(fileName: String, mode: String = "rw"): ParcelFileDescriptor?
        fun listRemoteFiles(): Array<String>?
        fun deleteRemoteFile(fileName: String): Boolean
    }

    private val fileAccessor: IFileAccessor? by lazy {
        ServiceManager.service?.let {
            object : IFileAccessor {
                override fun openRemoteFile(fileName: String, mode: String) = it.openRemoteFile(fileName)
                override fun listRemoteFiles(): Array<String>? = it.listRemoteFiles()
                override fun deleteRemoteFile(fileName: String) = it.deleteRemoteFile(fileName)
            }
        } ?: HookLogic.xposedInterface?.let {
            object : IFileAccessor {
                override fun openRemoteFile(fileName: String, mode: String) = it.openRemoteFile(fileName)
                override fun listRemoteFiles(): Array<String>? = it.listRemoteFiles()
                override fun deleteRemoteFile(fileName: String): Boolean = false
            }
        }
    }

    private val generalSettingsCache = MutableStateFlow<Map<String, Any>?>(null)
    val generalSettingsFlow = generalSettingsCache.asStateFlow()

    private fun getSettingsMap(): Map<String, Any> {
        return generalSettingsCache.value ?: synchronized(this) {
            generalSettingsCache.value ?: run {
                val map = readSettingsMapFromFile()
                generalSettingsCache.value = map
                map
            }
        }
    }

    private fun updateAndPersistSettings(transform: (MutableMap<String, Any>) -> Unit) {
        val currentMap = getSettingsMap().toMutableMap()
        transform(currentMap)
        val newMap = currentMap.toMap()
        generalSettingsCache.value = newMap

        ioScope.launch {
            settingsWriteLock.withLock {
                val json = GSON.toJson(newMap)
                writeTextToFile(FILE_GENERAL_SETTINGS, json)
            }
        }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean = getSettingsMap()[key] as? Boolean ?: defaultValue
    fun setBoolean(key: String, value: Boolean) = updateAndPersistSettings { it[key] = value }
    fun getString(key: String, defaultValue: String?): String? = getSettingsMap()[key] as? String ?: defaultValue
    fun setString(key: String, value: String?) = updateAndPersistSettings { if (value == null) it.remove(key) else it[key] = value }
    fun remove(key: String) = updateAndPersistSettings { it.remove(key) }
    fun clear() = updateAndPersistSettings { it.clear() }
    fun contains(key: String): Boolean = getSettingsMap().containsKey(key)
    fun getAll(): Map<String, *> = getSettingsMap()

    private val customHookCache = ConcurrentHashMap<String, List<CustomHookInfo>>()

    fun getCustomHookConfigs(packageName: String?): List<CustomHookInfo> {
        val effectiveKey = packageName ?: GLOBAL_KEY
        return customHookCache.getOrPut(effectiveKey) {
            val fileName = buildFileName(FILE_PREFIX_CUSTOM_HOOK, effectiveKey)
            val content = readAllTextFromFile(fileName)
            if (content.isEmpty()) {
                emptyList()
            } else {
                try {
                    GSON.fromJson(content, object : TypeToken<List<CustomHookInfo>>() {}.type) ?: emptyList()
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing JSON from file: $fileName", e)
                    emptyList()
                }
            }
        }
    }

    fun setCustomHookConfigs(packageName: String?, configs: List<CustomHookInfo>) {
        val effectiveKey = packageName ?: GLOBAL_KEY
        if (configs.isEmpty()) {
            customHookCache.remove(effectiveKey)
        } else {
            customHookCache[effectiveKey] = configs
        }

        ioScope.launch {
            val fileName = buildFileName(FILE_PREFIX_CUSTOM_HOOK, effectiveKey)
            if (configs.isEmpty()) {
                deleteConfigFile(fileName)
            } else {
                val json = GSON.toJson(configs)
                if (!writeTextToFile(fileName, json)) {
                    Log.e(TAG, "Failed to save hooks to file: $fileName")
                }
            }
        }
    }

    fun invalidateCaches() {
        synchronized(this) {
            generalSettingsCache.value = null
        }
        customHookCache.clear()
        Log.d(TAG, "All caches invalidated.")
    }

    private fun readSettingsMapFromFile(): Map<String, Any> {
        val content = readAllTextFromFile(FILE_GENERAL_SETTINGS)
        return if (content.isNotEmpty()) {
            try {
                GSON.fromJson(content, object : TypeToken<Map<String, Any>>() {}.type) ?: emptyMap()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse $FILE_GENERAL_SETTINGS", e)
                emptyMap()
            }
        } else emptyMap()
    }

    private fun readAllTextFromFile(fileName: String): String {
        if (fileAccessor == null || !remoteFileExists(fileName)) return ""
        return try {
            fileAccessor?.openRemoteFile(fileName, "r")?.use { pfd ->
                InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(pfd), StandardCharsets.UTF_8).use { it.readText() }
            } ?: ""
        } catch (e: Exception) {
            if (e !is FileNotFoundException) Log.e(TAG, "Error reading text from file: $fileName", e)
            ""
        }
    }

    private fun writeTextToFile(fileName: String, content: String): Boolean {
        if (fileAccessor == null) return false
        return try {
            fileAccessor?.openRemoteFile(fileName, "wt")?.use { pfd ->
                ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { fos ->
                    OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                        writer.write(content)
                        writer.flush()
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write text to file: $fileName", e)
            false
        }
    }

    private fun deleteConfigFile(fileName: String): Boolean = try {
        fileAccessor?.deleteRemoteFile(fileName) ?: false
    } catch (e: Exception) {
        Log.e(TAG, "Failed to delete config file: $fileName", e)
        false
    }

    private fun remoteFileExists(fileName: String): Boolean = try {
        fileAccessor?.listRemoteFiles()?.contains(fileName) == true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to list remote files to check existence", e)
        false
    }

    internal fun buildKey(prefix: String, packageName: String?): String = prefix + (packageName ?: GLOBAL_KEY)
    private fun buildFileName(prefix: String, key: String): String = "$prefix$key.json"

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
}
