package com.close.hook.ads.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.close.hook.ads.preference.HookPrefs
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class HookPrefsService : Service() {

    private val TAG = "HookPrefsService"
    private lateinit var prefs: android.content.SharedPreferences
    private val GSON = Gson()

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(HookPrefs.PREF_NAME, Context.MODE_PRIVATE)
        Log.d(TAG, "HookPrefsService created.")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "HookPrefsService bound.")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "HookPrefsService unbound.")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "HookPrefsService destroyed.")
    }

    private val binder = object : IHookPrefsAidlInterface.Stub() {

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
            return prefs.getBoolean(key, defaultValue)
        }

        override fun setBoolean(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }

        override fun getInt(key: String, defaultValue: Int): Int {
            return prefs.getInt(key, defaultValue)
        }

        override fun setInt(key: String, value: Int) {
            prefs.edit().putInt(key, value).apply()
        }

        override fun getLong(key: String, defaultValue: Long): Long {
            return prefs.getLong(key, defaultValue)
        }

        override fun setLong(key: String, value: Long) {
            prefs.edit().putLong(key, value).apply()
        }

        override fun getFloat(key: String, defaultValue: Float): Float {
            return prefs.getFloat(key, defaultValue)
        }

        override fun setFloat(key: String, value: Float) {
            prefs.edit().putFloat(key, value).apply()
        }

        override fun getString(key: String, defaultValue: String): String {
            return prefs.getString(key, defaultValue) ?: defaultValue
        }

        override fun setString(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }

        override fun contains(key: String): Boolean {
            return prefs.contains(key)
        }

        override fun remove(key: String) {
            prefs.edit().remove(key).apply()
        }

        override fun clear() {
            prefs.edit().clear().apply()
        }

        override fun getCustomHookConfigsJson(packageName: String?): String {
            val key = if (packageName.isNullOrEmpty()) HookPrefs.CUSTOM_HOOK_CONFIGS_KEY_PREFIX + "global"
                      else HookPrefs.CUSTOM_HOOK_CONFIGS_KEY_PREFIX + packageName
            return prefs.getString(key, "[]") ?: "[]"
        }

        override fun setCustomHookConfigsJson(packageName: String?, configsJson: String?) {
            val key = if (packageName.isNullOrEmpty()) HookPrefs.CUSTOM_HOOK_CONFIGS_KEY_PREFIX + "global"
                      else HookPrefs.CUSTOM_HOOK_CONFIGS_KEY_PREFIX + packageName
            prefs.edit().putString(key, configsJson ?: "[]").apply()
        }

        override fun getAll(): Bundle {
            val allPrefs = prefs.all
            val bundle = Bundle()
            allPrefs.forEach { (k, v) ->
                when (v) {
                    is Boolean -> bundle.putBoolean(k, v)
                    is Int -> bundle.putInt(k, v)
                    is Long -> bundle.putLong(k, v)
                    is Float -> bundle.putFloat(k, v)
                    is String -> bundle.putString(k, v)
                    else -> Log.w(TAG, "Unsupported type in getAll: ${v?.javaClass?.simpleName} for key $k")
                }
            }
            return bundle
        }
    }
}
