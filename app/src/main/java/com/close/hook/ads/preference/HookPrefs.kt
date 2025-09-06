package com.close.hook.ads.preference

import android.os.ParcelFileDescriptor
import android.util.Log
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.hook.HookLogic
import com.close.hook.ads.manager.ServiceManager
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
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
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

object HookPrefs {

    private const val TAG = "HookPrefs"
    private const val GLOBAL_KEY = "global"

    private const val FILE_GENERAL_SETTINGS = "com.close.hook.ads_preferences.json"
    private const val FILE_PREFIX_CUSTOM_HOOK = "custom_hooks_"

    private const val KEY_PREFIX_OVERALL_HOOK = "overall_hook_enabled_"
    private const val KEY_PREFIX_ENABLE_LOGGING = "enable_logging_"
    const val KEY_COLLECT_RESPONSE_BODY = "collect_response_body_enabled"
    const val KEY_ENABLE_DEX_DUMP = "enable_dex_dump"
    const val KEY_REQUEST_CACHE_EXPIRATION = "request_cache_expiration"

    private val GSON by lazy { Gson() }
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settingsWriteLock = Mutex()

    private interface IFileAccessor {
        fun openRemoteFile(fileName: String, mode: String = "rw"): ParcelFileDescriptor?
        fun listRemoteFiles(): Array<String>?
        fun deleteRemoteFile(fileName: String): Boolean
    }

    private val fileAccessor: IFileAccessor? by lazy {
        ServiceManager.service?.let { service ->
            object : IFileAccessor {
                override fun openRemoteFile(fileName: String, mode: String) =
                    service.openRemoteFile(fileName)

                override fun listRemoteFiles(): Array<String>? = service.listRemoteFiles()
                override fun deleteRemoteFile(fileName: String) = service.deleteRemoteFile(fileName)
            }
        } ?: HookLogic.xposedInterface?.let { xposedInterface ->
            object : IFileAccessor {
                override fun openRemoteFile(fileName: String, mode: String) =
                    xposedInterface.openRemoteFile(fileName)

                override fun listRemoteFiles(): Array<String>? = xposedInterface.listRemoteFiles()
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

    private suspend fun updateAndPersistSettings(transform: (MutableMap<String, Any>) -> Unit) {
        settingsWriteLock.withLock {
            val currentSettings = readSettingsMapFromFile().toMutableMap()
            transform(currentSettings)

            val newSettings = currentSettings.toMap()
            val json = GSON.toJson(newSettings)
            val writeSuccess = writeTextToFile(FILE_GENERAL_SETTINGS, json)

            if (writeSuccess) {
                generalSettingsCache.value = newSettings
            }
        }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return getSettingsMap()[key] as? Boolean ?: defaultValue
    }

    fun setBoolean(key: String, value: Boolean) {
        ioScope.launch {
            updateAndPersistSettings { it[key] = value }
        }
    }
    
    fun getLong(key: String, defaultValue: Long): Long {
        return (getSettingsMap()[key] as? Double)?.toLong() ?: defaultValue
    }

    fun setLong(key: String, value: Long) {
        ioScope.launch {
            updateAndPersistSettings { it[key] = value }
        }
    }

    fun getString(key: String, defaultValue: String?): String? {
        return getSettingsMap()[key] as? String ?: defaultValue
    }

    fun setString(key: String, value: String?) {
        ioScope.launch {
            updateAndPersistSettings {
                if (value == null) it.remove(key) else it[key] = value
            }
        }
    }

    fun setMultiple(updates: Map<String, Any>) {
        if (updates.isEmpty()) return
        ioScope.launch {
            updateAndPersistSettings { it.putAll(updates) }
        }
    }

    fun remove(key: String) {
        ioScope.launch {
            updateAndPersistSettings { it.remove(key) }
        }
    }

    fun clear() {
        ioScope.launch {
            updateAndPersistSettings { it.clear() }
        }
    }

    fun contains(key: String): Boolean {
        return getSettingsMap().containsKey(key)
    }

    fun getAll(): Map<String, *> {
        return getSettingsMap()
    }

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
                    val type = object : TypeToken<List<CustomHookInfo>>() {}.type
                    GSON.fromJson(content, type) ?: emptyList()
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
                val type = object : TypeToken<Map<String, Any>>() {}.type
                GSON.fromJson(content, type) ?: emptyMap()
            } catch (e: JsonSyntaxException) {
                Log.e(TAG, "Failed to parse $FILE_GENERAL_SETTINGS due to malformed JSON. Returning empty map.", e)
                emptyMap()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse $FILE_GENERAL_SETTINGS", e)
                emptyMap()
            }
        } else {
            emptyMap()
        }
    }

    private fun readAllTextFromFile(fileName: String): String {
        if (fileAccessor == null || !remoteFileExists(fileName)) {
            return ""
        }
        return try {
            fileAccessor?.openRemoteFile(fileName, "r")?.use { pfd ->
                InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(pfd), StandardCharsets.UTF_8).use {
                    it.readText()
                }
            } ?: ""
        } catch (e: Exception) {
            if (e !is FileNotFoundException) {
                Log.e(TAG, "Error reading text from file: $fileName", e)
            }
            ""
        }
    }

    private fun writeTextToFile(fileName: String, content: String): Boolean {
        if (fileAccessor == null) {
            return false
        }
        return try {
            fileAccessor?.openRemoteFile(fileName, "rw")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { fos ->
                    fos.channel.truncate(0)

                    fos.writer(StandardCharsets.UTF_8).use { writer ->
                        writer.write(content)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write text to file: $fileName", e)
            false
        }
    }

    private fun deleteConfigFile(fileName: String): Boolean {
        return try {
            fileAccessor?.deleteRemoteFile(fileName) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete config file: $fileName", e)
            false
        }
    }

    private fun remoteFileExists(fileName: String): Boolean {
        return try {
            fileAccessor?.listRemoteFiles()?.contains(fileName) == true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list remote files to check existence", e)
            false
        }
    }

    internal fun buildKey(prefix: String, packageName: String?): String {
        return prefix + (packageName ?: GLOBAL_KEY)
    }

    private fun buildFileName(prefix: String, key: String): String {
        return "$prefix$key.json"
    }

    fun getOverallHookEnabled(packageName: String?): Boolean {
        return getBoolean(buildKey(KEY_PREFIX_OVERALL_HOOK, packageName), false)
    }

    fun setOverallHookEnabled(packageName: String?, isEnabled: Boolean) {
        setBoolean(buildKey(KEY_PREFIX_OVERALL_HOOK, packageName), isEnabled)
    }

    fun getEnableLogging(packageName: String?): Boolean {
        return getBoolean(buildKey(KEY_PREFIX_ENABLE_LOGGING, packageName), false)
    }

    fun setEnableLogging(packageName: String?, isEnabled: Boolean) {
        setBoolean(buildKey(KEY_PREFIX_ENABLE_LOGGING, packageName), isEnabled)
    }

    fun getRequestCacheExpiration(): Long {
        return getString(KEY_REQUEST_CACHE_EXPIRATION, "5")?.toLongOrNull() ?: 5L
    }
}
