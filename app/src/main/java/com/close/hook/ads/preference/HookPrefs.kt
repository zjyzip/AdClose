package com.close.hook.ads.preference

import android.os.ParcelFileDescriptor
import android.util.Log
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.hook.HookLogic
import com.close.hook.ads.manager.ServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
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

    private val jsonFormat = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true 
        isLenient = true
    }

    private val ioScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())

    private interface IFileAccessor {
        fun openRemoteFile(fileName: String, mode: String = "rw"): ParcelFileDescriptor?
        fun listRemoteFiles(): Array<String>?
        fun deleteRemoteFile(fileName: String): Boolean
    }

    private val fileAccessor: IFileAccessor?
        get() {
            ServiceManager.service?.let { service ->
                return object : IFileAccessor {
                    override fun openRemoteFile(fileName: String, mode: String) =
                        service.openRemoteFile(fileName)
                    override fun listRemoteFiles(): Array<String>? = service.listRemoteFiles()
                    override fun deleteRemoteFile(fileName: String) = service.deleteRemoteFile(fileName)
                }
            }
            HookLogic.xposedInterface?.let { xposedInterface ->
                return object : IFileAccessor {
                    override fun openRemoteFile(fileName: String, mode: String) =
                        xposedInterface.openRemoteFile(fileName)
                    override fun listRemoteFiles(): Array<String>? = xposedInterface.listRemoteFiles()
                    override fun deleteRemoteFile(fileName: String): Boolean = false
                }
            }
            return null
        }

    private val generalSettingsCache = MutableStateFlow<JsonObject?>(null)
    val generalSettingsFlow = generalSettingsCache.asStateFlow()
    
    private val cacheLock = Any()

    private fun getSettingsJson(): JsonObject {
        val cached = generalSettingsCache.value
        if (cached != null) return cached

        synchronized(cacheLock) {
            val cachedAgain = generalSettingsCache.value
            if (cachedAgain != null) return cachedAgain

            val jsonObject = readSettingsFromFile()
            generalSettingsCache.value = jsonObject
            return jsonObject
        }
    }

    private fun updateSetting(transform: (MutableMap<String, JsonElement>) -> Unit) {
        val current = getSettingsJson()

        val newJson: JsonObject
        synchronized(cacheLock) {
            val mutableMap = current.toMutableMap()
            transform(mutableMap)
            newJson = JsonObject(mutableMap)
            generalSettingsCache.value = newJson
        }

        ioScope.launch {
            try {
                val jsonString = jsonFormat.encodeToString(newJson)
                writeTextToFile(FILE_GENERAL_SETTINGS, jsonString)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist settings", e)
            }
        }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val element = getSettingsJson()[key]
        return if (element is JsonPrimitive) {
            element.booleanOrNull ?: defaultValue
        } else {
            defaultValue
        }
    }

    fun setBoolean(key: String, value: Boolean) {
        updateSetting { it[key] = JsonPrimitive(value) }
    }
    
    fun getLong(key: String, defaultValue: Long): Long {
        val element = getSettingsJson()[key]
        return if (element is JsonPrimitive) {
            element.longOrNull ?: element.contentOrNull?.toLongOrNull() ?: defaultValue
        } else {
            defaultValue
        }
    }

    fun setLong(key: String, value: Long) {
        updateSetting { it[key] = JsonPrimitive(value) }
    }

    fun getString(key: String, defaultValue: String?): String? {
        val element = getSettingsJson()[key]
        return if (element is JsonPrimitive) {
            element.contentOrNull ?: defaultValue
        } else {
            defaultValue
        }
    }

    fun setString(key: String, value: String?) {
        updateSetting {
            if (value == null) it.remove(key) else it[key] = JsonPrimitive(value)
        }
    }

    fun setMultiple(updates: Map<String, Any>) {
        if (updates.isEmpty()) return
        updateSetting { map ->
            updates.forEach { (k, v) ->
                val jsonElement = when (v) {
                    is Boolean -> JsonPrimitive(v)
                    is Number -> JsonPrimitive(v)
                    is String -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                }
                map[k] = jsonElement
            }
        }
    }

    fun remove(key: String) {
        updateSetting { it.remove(key) }
    }

    fun clear() {
        updateSetting { it.clear() }
    }

    fun contains(key: String): Boolean {
        return getSettingsJson().containsKey(key)
    }

    fun getAll(): Map<String, Any?> {
        val json = getSettingsJson()
        return json.mapValues { entry ->
            val primitive = entry.value as? JsonPrimitive
            when {
                primitive?.isString == true -> primitive.content
                primitive != null -> {
                    primitive.booleanOrNull 
                        ?: primitive.longOrNull 
                        ?: primitive.doubleOrNull 
                        ?: primitive.content
                }
                else -> entry.value.toString()
            }
        }
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
                    jsonFormat.decodeFromString<List<CustomHookInfo>>(content)
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
                try {
                    val jsonString = jsonFormat.encodeToString(configs)
                    if (!writeTextToFile(fileName, jsonString)) {
                        Log.e(TAG, "Failed to save hooks to file: $fileName")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Serialization error for hooks", e)
                }
            }
        }
    }

    fun invalidateCaches() {
        synchronized(cacheLock) {
            generalSettingsCache.value = null
        }
        customHookCache.clear()
        Log.d(TAG, "All caches invalidated.")
    }

    private fun readSettingsFromFile(): JsonObject {
        val content = readAllTextFromFile(FILE_GENERAL_SETTINGS)
        return if (content.isNotEmpty()) {
            try {
                jsonFormat.parseToJsonElement(content) as? JsonObject ?: JsonObject(emptyMap())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse settings JSON", e)
                JsonObject(emptyMap())
            }
        } else {
            JsonObject(emptyMap())
        }
    }

    private fun readAllTextFromFile(fileName: String): String {
        val accessor = fileAccessor ?: return ""
        return try {
            accessor.openRemoteFile(fileName, "r")?.use { pfd ->
                InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(pfd), StandardCharsets.UTF_8).use {
                    it.readText()
                }
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun writeTextToFile(fileName: String, content: String): Boolean {
        val accessor = fileAccessor ?: return false
        return try {
            accessor.openRemoteFile(fileName, "rw")?.use { pfd ->
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
