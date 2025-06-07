package com.close.hook.ads

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.close.hook.ads.util.PrefManager
import com.close.hook.ads.util.PrefManager.darkTheme
import com.microsoft.appcenter.AppCenter
import com.microsoft.appcenter.analytics.Analytics
import com.microsoft.appcenter.crashes.Crashes
import rikka.material.app.LocaleDelegate
import java.util.Locale

lateinit var closeApp: CloseApplication

class CloseApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        closeApp = this

        initAppCenter()
        AppCompatDelegate.setDefaultNightMode(darkTheme)
        applyLocale(PrefManager.language)
    }

    private fun initAppCenter() {
        AppCenter.start(
            this,
            "621cdb49-4473-44d3-a8f8-e76f28ba43d7",
            Analytics::class.java,
            Crashes::class.java
        )
    }

    private fun applyLocale(languageTag: String) {
        LocaleDelegate.defaultLocale = getLocale(languageTag)
        val config = resources.configuration
        config.setLocale(LocaleDelegate.defaultLocale)
        createConfigurationContext(config)
    }

    fun getLocale(tag: String): Locale {
        return if (tag == "SYSTEM") {
            LocaleDelegate.systemLocale
        } else {
            Locale.forLanguageTag(tag)
        }
    }
}
