package com.close.hook.ads.provider

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import com.close.hook.ads.service.IHookPrefsAidlInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HookPrefsProvider : ContentProvider() {

    companion object {
        private const val PREF_NAME = "com.close.hook.ads_preferences"
        private const val URI_CODE = 1
        const val AUTHORITY = "com.close.hook.ads.provider.prefs"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PREF_NAME")

        const val METHOD_GET_VALUE = "get_value"
        const val METHOD_SET_VALUE = "set_value"
        const val METHOD_CONTAINS_KEY = "contains_key"
        const val METHOD_REMOVE_KEY = "remove_key"
        const val METHOD_CLEAR_ALL = "clear_all"
        const val METHOD_GET_ALL = "get_all"
        const val METHOD_GET_CUSTOM_HOOK_CONFIGS = "get_custom_hook_configs"
        const val METHOD_SET_CUSTOM_HOOK_CONFIGS = "set_custom_hook_configs"

        const val ARG_KEY = "key"
        const val ARG_VALUE = "value"
        const val ARG_DEFAULT_VALUE = "default_value"
        const val ARG_TYPE = "type"
        const val ARG_PACKAGE_NAME = "package_name"
        const val ARG_CONFIGS_JSON = "configs_json"

        const val RESULT_VALUE = "result_value"
        const val RESULT_BOOLEAN = "result_boolean"
    }

    private val uriMatcher by lazy {
        UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PREF_NAME, URI_CODE)
        }
    }

    @Volatile
    private var aidlService: IHookPrefsAidlInterface? = null
    private lateinit var serviceConnection: ServiceConnection
    private val serviceLatch = CountDownLatch(1)

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    aidlService = IHookPrefsAidlInterface.Stub.asInterface(service)
                    serviceLatch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    aidlService = null
                    bindService(ctx)
                }
            }
            bindService(ctx)
        }
        return true
    }

    private fun bindService(ctx: Context) {
        val intent = Intent(ctx, com.close.hook.ads.service.HookPrefsService::class.java)
        ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        throw UnsupportedOperationException("Query not supported. Use call method instead.")
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.${AUTHORITY}.$PREF_NAME"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Insert not supported. Use call method for setting values.")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("Delete not supported. Use call method for removing values or clearing all.")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("Update not supported. Use call method for setting values.")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (uriMatcher.match(CONTENT_URI) != URI_CODE) return null

        try {
            if (!serviceLatch.await(5, TimeUnit.SECONDS)) return null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }

        val service = aidlService ?: return null
        val resultBundle = Bundle()

        return try {
            when (method) {
                METHOD_GET_VALUE -> {
                    val key = extras?.getString(ARG_KEY) ?: return null
                    val defaultValue = extras.getString(ARG_DEFAULT_VALUE)
                    val type = extras.getString(ARG_TYPE) ?: return null

                    val value: Any? = when (type) {
                        "boolean" -> service.getBoolean(key, defaultValue?.toBooleanStrictOrNull() ?: false)
                        "int" -> service.getInt(key, defaultValue?.toIntOrNull() ?: 0)
                        "long" -> service.getLong(key, defaultValue?.toLongOrNull() ?: 0L)
                        "float" -> service.getFloat(key, defaultValue?.toFloatOrNull() ?: 0f)
                        "string" -> service.getString(key, defaultValue ?: "")
                        else -> null
                    }
                    value?.let {
                        when (it) {
                            is Boolean -> resultBundle.putBoolean(RESULT_VALUE, it)
                            is Int -> resultBundle.putInt(RESULT_VALUE, it)
                            is Long -> resultBundle.putLong(RESULT_VALUE, it)
                            is Float -> resultBundle.putFloat(RESULT_VALUE, it)
                            is String -> resultBundle.putString(RESULT_VALUE, it)
                        }
                    }
                }
                METHOD_SET_VALUE -> {
                    val key = extras?.getString(ARG_KEY) ?: return null
                    val type = extras.getString(ARG_TYPE) ?: return null

                    when (type) {
                        "boolean" -> service.setBoolean(key, extras.getBoolean(ARG_VALUE))
                        "int" -> service.setInt(key, extras.getInt(ARG_VALUE))
                        "long" -> service.setLong(key, extras.getLong(ARG_VALUE))
                        "float" -> service.setFloat(key, extras.getFloat(ARG_VALUE))
                        "string" -> extras.getString(ARG_VALUE)?.let { service.setString(key, it) }
                        else -> {}
                    }
                    resultBundle.putBoolean(RESULT_BOOLEAN, true)
                }
                METHOD_CONTAINS_KEY -> {
                    val key = extras?.getString(ARG_KEY) ?: return null
                    resultBundle.putBoolean(RESULT_BOOLEAN, service.contains(key))
                }
                METHOD_REMOVE_KEY -> {
                    val key = extras?.getString(ARG_KEY) ?: return null
                    service.remove(key)
                    resultBundle.putBoolean(RESULT_BOOLEAN, true)
                }
                METHOD_CLEAR_ALL -> {
                    service.clear()
                    resultBundle.putBoolean(RESULT_BOOLEAN, true)
                }
                METHOD_GET_CUSTOM_HOOK_CONFIGS -> {
                    val packageName = extras?.getString(ARG_PACKAGE_NAME)
                    val configsJson = service.getCustomHookConfigsJson(packageName)
                    resultBundle.putString(RESULT_VALUE, configsJson)
                }
                METHOD_SET_CUSTOM_HOOK_CONFIGS -> {
                    val packageName = extras?.getString(ARG_PACKAGE_NAME)
                    val configsJson = extras?.getString(ARG_CONFIGS_JSON)
                    if (configsJson != null) {
                        service.setCustomHookConfigsJson(packageName, configsJson)
                        resultBundle.putBoolean(RESULT_BOOLEAN, true)
                    } else {
                        resultBundle.putBoolean(RESULT_BOOLEAN, false)
                    }
                }
                METHOD_GET_ALL -> {
                    val allDataBundle = service.getAll()
                    resultBundle.putBundle(RESULT_VALUE, allDataBundle)
                }
                else -> return null
            }
            resultBundle
        } catch (e: Exception) {
            null
        }
    }
}
