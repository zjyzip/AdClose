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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileNotFoundException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object HookPrefs {

    private val GSON by lazy { Gson() }

    private const val FILE_GENERAL_SETTINGS = "com.close.hook.ads_preferences.json"
    private const val FILE_PREFIX_CUSTOM_HOOK = "custom_hooks_"
    
    private const val KEY_PREFIX_OVERALL_HOOK = "overall_hook_enabled_"
    private const val KEY_PREFIX_DETECTED_HOOK = "detected_hook_configs_"
    private const val KEY_PREFIX_ENABLE_LOGGING = "enable_logging_"
    const val KEY_COLLECT_RESPONSE_BODY = "collect_response_body_enabled"

    private val settings = ConcurrentHashMap<String, Any>()
    private val settingsLoaded = AtomicBoolean(false)
    private val lock = Any()
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeLock = Mutex()

    fun buildKey(prefix: String, packageName: String?): String = prefix + (packageName ?: "global")
    private fun buildFileName(prefix: String, packageName: String?): String = prefix + (packageName ?: "global") + ".json"

    private fun loadSettingsIfNeededBlocking() {
        if (settingsLoaded.get()) return
        synchronized(lock) {
            if (settingsLoaded.get()) return

            val content = readAllTextFromFile(FILE_GENERAL_SETTINGS)
            if (content.isNotEmpty()) {
                try {
                    val type = object : TypeToken<ConcurrentHashMap<String, Any>>() {}.type
                    val loadedSettings: ConcurrentHashMap<String, Any> = GSON.fromJson(content, type)
                    settings.putAll(loadedSettings)
                } catch (e: Exception) {
                    Log.e("HookPrefs", "Failed to parse general_settings.json", e)
                }
            }
            settingsLoaded.set(true)
        }
    }

    private fun saveSettings() {
        ioScope.launch {
            writeLock.withLock {
                val settingsSnapshot = HashMap(settings)
                writeTextToFile(FILE_GENERAL_SETTINGS, GSON.toJson(settingsSnapshot))
            }
        }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        loadSettingsIfNeededBlocking()
        return settings[key] as? Boolean ?: defaultValue
    }

    fun setBoolean(key: String, value: Boolean) {
        loadSettingsIfNeededBlocking()
        settings[key] = value
        saveSettings()
    }
    
    fun getString(key: String, defaultValue: String?): String? {
        loadSettingsIfNeededBlocking()
        return settings[key] as? String ?: defaultValue
    }

    fun setString(key: String, value: String?) {
        loadSettingsIfNeededBlocking()
        if (value == null) {
            settings.remove(key)
        } else {
            settings[key] = value
        }
        saveSettings()
    }

    fun remove(key: String) {
        loadSettingsIfNeededBlocking()
        settings.remove(key)
        saveSettings()
    }

    fun clear() {
        loadSettingsIfNeededBlocking()
        settings.clear()
        saveSettings()
    }
    
    fun contains(key: String): Boolean {
        loadSettingsIfNeededBlocking()
        return settings.containsKey(key)
    }

    fun getAll(): Map<String, *> {
        loadSettingsIfNeededBlocking()
        return HashMap(settings)
    }

    fun getCustomHookConfigs(packageName: String?): List<CustomHookInfo> {
        val fileName = buildFileName(FILE_PREFIX_CUSTOM_HOOK, packageName)
        val content = readAllTextFromFile(fileName)
        return if (content.isEmpty()) {
            emptyList()
        } else {
            try {
                GSON.fromJson(content, object : TypeToken<List<CustomHookInfo>>() {}.type) ?: emptyList()
            } catch (e: Exception) {
                Log.e("HookPrefs", "Error parsing JSON from file: $fileName", e)
                emptyList()
            }
        }
    }

    fun setCustomHookConfigs(packageName: String?, configs: List<CustomHookInfo>) {
        val fileName = buildFileName(FILE_PREFIX_CUSTOM_HOOK, packageName)
        if (configs.isEmpty()) {
            deleteConfigFile(fileName)
        } else {
            val json = GSON.toJson(configs)
            if (!writeTextToFile(fileName, json)) {
                Log.e("HookPrefs", "Failed to save hooks to file: $fileName")
            }
        }
    }

    private fun openRemoteFileForCurrentContext(fileName: String): ParcelFileDescriptor? = try {
        if (ServiceManager.service != null) {
            ServiceManager.service?.openRemoteFile(fileName)
        } else {
            HookLogic.xposedInterface?.openRemoteFile(fileName)
        }
    } catch (e: Exception) {
        if (e !is FileNotFoundException) {
            Log.e("HookPrefs", "Error opening remote file '$fileName'", e)
        }
        null
    }

    private fun remoteFileExists(fileName: String): Boolean {
        val files: Array<String>? = try {
            if (ServiceManager.service != null) {
                ServiceManager.service?.listRemoteFiles()
            } else {
                HookLogic.xposedInterface?.listRemoteFiles()
            }
        } catch (e: Exception) {
            Log.e("HookPrefs", "Failed to list remote files to check existence", e)
            null
        }
        return files?.contains(fileName) == true
    }

    private fun readAllTextFromFile(fileName: String): String {
        if (!remoteFileExists(fileName)) return ""
        val pfd = openRemoteFileForCurrentContext(fileName) ?: return ""
        return try {
            InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(pfd), StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.e("HookPrefs", "Error reading text from file: $fileName", e)
            ""
        }
    }

    private fun writeTextToFile(fileName: String, content: String): Boolean {
        val service = ServiceManager.service ?: return false
        return try {
            service.openRemoteFile(fileName).use { pfd ->
                OutputStreamWriter(ParcelFileDescriptor.AutoCloseOutputStream(pfd), StandardCharsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                }
            }
            true
        } catch (e: Exception) {
            Log.e("HookPrefs", "Failed to write text to file: $fileName", e)
            false
        }
    }
    
    private fun deleteConfigFile(fileName: String) {
        val service = ServiceManager.service ?: return
        try {
            service.deleteRemoteFile(fileName)
        } catch (e: Exception) {
            Log.e("HookPrefs", "Failed to delete config file: $fileName", e)
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
}
