package com.close.hook.ads

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.close.hook.ads.provider.DataManager.Companion.serverStub
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

        val bundle = Bundle()
        bundle.putBinder("binder", serverStub)
        contentResolver.call(
            Uri.parse("content://com.close.hook.ads"),
            "getBinder",
            null,
            bundle
        )

        AppCenter.start(
            this, "621cdb49-4473-44d3-a8f8-e76f28ba43d7",
            Analytics::class.java, Crashes::class.java
        )

        AppCompatDelegate.setDefaultNightMode(darkTheme)

        LocaleDelegate.defaultLocale = getLocale(PrefManager.language)
        val config = resources.configuration
        config.setLocale(LocaleDelegate.defaultLocale)
        createConfigurationContext(config)

    }

    fun getLocale(tag: String): Locale {
        return if (tag == "SYSTEM") LocaleDelegate.systemLocale
        else Locale.forLanguageTag(tag)
    }


}