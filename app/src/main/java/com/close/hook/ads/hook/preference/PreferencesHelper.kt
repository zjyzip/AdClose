package com.close.hook.ads.hook.preference

import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XSharedPreferences
import java.util.Optional

class PreferencesHelper {

    companion object {
        private const val PREF_NAME = "com.close.hook.ads_preferences"
    }

    private val prefs: Optional<SharedPreferences>
    private val xPrefs: Optional<XSharedPreferences>

    /*
    constructor(context: Context) {
        prefs = Optional.of(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE))
        xPrefs = Optional.empty()

        try {
            val prefFile = File("/data/data/${context.packageName}/shared_prefs/$PREF_NAME.xml")
            if (prefFile.exists()) {
                prefFile.setReadable(true, false)
            }
        } catch (ignored: Exception) {
        }
    }
    */

    constructor(context: Context) {
        prefs = Optional.of(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE))
        xPrefs = Optional.empty()
    }

    constructor() {
        xPrefs = Optional.of(XSharedPreferences("com.close.hook.ads", PREF_NAME)).also {
            it.ifPresent { xp ->
                xp.makeWorldReadable()
                xp.reload()
            }
        }
        prefs = Optional.empty()
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return when {
            prefs.isPresent -> prefs.get().getBoolean(key, defaultValue)
            xPrefs.isPresent -> {
                xPrefs.get().reload()
                xPrefs.get().getBoolean(key, defaultValue)
            }
            else -> defaultValue
        }
    }

    fun setBoolean(key: String, value: Boolean) {
        prefs.ifPresent { it.edit().putBoolean(key, value).apply() }
    }

    fun getInt(key: String, defaultValue: Int): Int {
        return when {
            prefs.isPresent -> prefs.get().getInt(key, defaultValue)
            xPrefs.isPresent -> {
                xPrefs.get().reload()
                xPrefs.get().getInt(key, defaultValue)
            }
            else -> defaultValue
        }
    }

    fun setInt(key: String, value: Int) {
        prefs.ifPresent { it.edit().putInt(key, value).apply() }
    }

    fun getLong(key: String, defaultValue: Long): Long {
        return when {
            prefs.isPresent -> prefs.get().getLong(key, defaultValue)
            xPrefs.isPresent -> {
                xPrefs.get().reload()
                xPrefs.get().getLong(key, defaultValue)
            }
            else -> defaultValue
        }
    }

    fun putLong(key: String, value: Long) {
        prefs.ifPresent { it.edit().putLong(key, value).apply() }
    }

    fun getFloat(key: String, defaultValue: Float): Float {
        return when {
            prefs.isPresent -> prefs.get().getFloat(key, defaultValue)
            xPrefs.isPresent -> {
                xPrefs.get().reload()
                xPrefs.get().getFloat(key, defaultValue)
            }
            else -> defaultValue
        }
    }

    fun setFloat(key: String, value: Float) {
        prefs.ifPresent { it.edit().putFloat(key, value).apply() }
    }

    fun getString(key: String, defaultValue: String): String {
        return when {
            prefs.isPresent -> prefs.get().getString(key, defaultValue) ?: defaultValue
            xPrefs.isPresent -> {
                xPrefs.get().reload()
                xPrefs.get().getString(key, defaultValue) ?: defaultValue
            }
            else -> defaultValue
        }
    }

    fun setString(key: String, value: String) {
        prefs.ifPresent { it.edit().putString(key, value).apply() }
    }

    fun contains(key: String): Boolean {
        return when {
            prefs.isPresent -> prefs.get().contains(key)
            xPrefs.isPresent -> {
                xPrefs.get().reload()
                xPrefs.get().contains(key)
            }
            else -> false
        }
    }

    fun remove(key: String) {
        prefs.ifPresent { it.edit().remove(key).apply() }
    }

    fun clear() {
        prefs.ifPresent { it.edit().clear().apply() }
    }
}
