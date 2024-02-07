package com.close.hook.ads.util

import android.content.ComponentName
import android.content.Context.MODE_PRIVATE
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatDelegate
import com.close.hook.ads.closeApp

object PrefManager {

    private const val PREF_DARK_THEME = "dark_theme"
    private const val PREF_BLACK_DARK_THEME = "black_dark_theme"
    private const val PREF_FOLLOW_SYSTEM_ACCENT = "follow_system_accent"
    private const val PREF_THEME_COLOR = "theme_color"

    private val pref = closeApp.getSharedPreferences("settings", MODE_PRIVATE)

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
        get() = pref.getString(PREF_THEME_COLOR, "MATERIAL_DEFAULT")!!
        set(value) = pref.edit().putString(PREF_THEME_COLOR, value).apply()

    var hideIcon: Boolean
        get() = pref.getBoolean("hideIcon", false)
        set(value) {
            pref.edit().putBoolean("hideIcon", value).apply()
            val component = ComponentName(closeApp, "com.close.hook.ads.MainActivityLauncher")
            val status =
                if (value) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            closeApp.packageManager.setComponentEnabledSetting(component, status, PackageManager.DONT_KILL_APP)
        }

    var order: String
        get() = pref.getString("order", "应用名称")!!
        set(value) = pref.edit().putString("order", value).apply()

    var configured: Boolean
        get() = pref.getBoolean("configured", false)
        set(value) =pref.edit().putBoolean("configured", value).apply()

    var updated: Boolean
        get() = pref.getBoolean("updated", false)
        set(value) =pref.edit().putBoolean("updated", value).apply()

    var disabled: Boolean
        get() = pref.getBoolean("disabled", false)
        set(value) =pref.edit().putBoolean("disabled", value).apply()

    var isReverse: Boolean
        get() = pref.getBoolean("isReverse", false)
        set(value) =pref.edit().putBoolean("isReverse", value).apply()

    var setData: Boolean
        get() = pref.getBoolean("setData", true)
        set(value) =pref.edit().putBoolean("setData", value).apply()

    var language: String
        get() = pref.getString("language", "SYSTEM")!!
        set(value) = pref.edit().putString("language", value).apply()

}