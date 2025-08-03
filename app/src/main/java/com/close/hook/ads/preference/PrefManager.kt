package com.close.hook.ads.preference

import android.content.ComponentName
import android.content.Context.MODE_PRIVATE
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatDelegate
import com.close.hook.ads.R
import com.close.hook.ads.closeApp

object PrefManager {

    private const val PREF_DARK_THEME = "dark_theme"
    private const val PREF_BLACK_DARK_THEME = "black_dark_theme"
    private const val PREF_FOLLOW_SYSTEM_ACCENT = "follow_system_accent"
    private const val PREF_THEME_COLOR = "theme_color"
    private const val PREF_HIDE_ICON = "hideIcon"
    private const val PREF_ORDER_ID = "order_id"
    private const val PREF_CONFIGURED = "configured"
    private const val PREF_UPDATED = "updated"
    private const val PREF_DISABLED = "disabled"
    private const val PREF_IS_REVERSE = "isReverse"
    private const val PREF_SET_DATA = "setData"
    private const val PREF_LANGUAGE = "language"
    private const val PREF_DEFAULT_PAGE = "defaultPage"

    private val pref by lazy { closeApp.getSharedPreferences("settings", MODE_PRIVATE) }

    var darkTheme: Int
        get() = pref.getInt(PREF_DARK_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = pref.edit().putInt(PREF_DARK_THEME, value).apply()

    var blackDarkTheme: Boolean
        get() = pref.getBoolean(PREF_BLACK_DARK_THEME, false)
        set(value) = pref.edit().putBoolean(PREF_BLACK_DARK_THEME, value).apply()

    var followSystemAccent: Boolean
        get() = pref.getBoolean(PREF_FOLLOW_SYSTEM_ACCENT, true)
        set(value) = pref.edit().putBoolean(PREF_FOLLOW_SYSTEM_ACCENT, value).apply()

    var themeColor: String
        get() = pref.getString(PREF_THEME_COLOR, "MATERIAL_DEFAULT") ?: "MATERIAL_DEFAULT"
        set(value) = pref.edit().putString(PREF_THEME_COLOR, value).apply()

    var hideIcon: Boolean
        get() = pref.getBoolean(PREF_HIDE_ICON, false)
        set(value) {
            pref.edit().putBoolean(PREF_HIDE_ICON, value).apply()
            val component = ComponentName(closeApp, "com.close.hook.ads.MainActivityLauncher")
            val status =
                if (value) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            closeApp.packageManager.setComponentEnabledSetting(
                component,
                status,
                PackageManager.DONT_KILL_APP
            )
        }

    var order: Int
        get() = pref.getInt(PREF_ORDER_ID, 0)
        set(value) = pref.edit().putInt(PREF_ORDER_ID, value).apply()

    var configured: Boolean
        get() = pref.getBoolean(PREF_CONFIGURED, false)
        set(value) = pref.edit().putBoolean(PREF_CONFIGURED, value).apply()

    var updated: Boolean
        get() = pref.getBoolean(PREF_UPDATED, false)
        set(value) = pref.edit().putBoolean(PREF_UPDATED, value).apply()

    var disabled: Boolean
        get() = pref.getBoolean(PREF_DISABLED, false)
        set(value) = pref.edit().putBoolean(PREF_DISABLED, value).apply()

    var isReverse: Boolean
        get() = pref.getBoolean(PREF_IS_REVERSE, false)
        set(value) = pref.edit().putBoolean(PREF_IS_REVERSE, value).apply()

    var setData: Boolean
        get() = pref.getBoolean(PREF_SET_DATA, true)
        set(value) = pref.edit().putBoolean(PREF_SET_DATA, value).apply()

    var language: String
        get() = pref.getString(PREF_LANGUAGE, "SYSTEM") ?: "SYSTEM"
        set(value) = pref.edit().putString(PREF_LANGUAGE, value).apply()

    var defaultPage: Int
        get() = pref.getInt(PREF_DEFAULT_PAGE, 2)
        set(value) = pref.edit().putInt(PREF_DEFAULT_PAGE, value).apply()

    fun updatePreferences(block: (editor: android.content.SharedPreferences.Editor) -> Unit) {
        pref.edit().apply {
            block(this)
            apply()
        }
    }
}
