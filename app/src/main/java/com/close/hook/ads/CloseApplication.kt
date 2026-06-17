package com.close.hook.ads

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.close.hook.ads.preference.PrefManager
import com.close.hook.ads.preference.PrefManager.darkTheme
import com.close.hook.ads.manager.ServiceManager
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.microsoft.appcenter.AppCenter
import com.microsoft.appcenter.analytics.Analytics
import com.microsoft.appcenter.crashes.Crashes

lateinit var closeApp: CloseApplication

class CloseApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        closeApp = this

        ServiceManager.init()

        initAppCenter()
        AppCompatDelegate.setDefaultNightMode(darkTheme)
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                .setPrecondition { _, _ -> PrefManager.followSystemAccent }
                .build()
        )
    }

    private fun initAppCenter() {
        AppCenter.start(
            this,
            "621cdb49-4473-44d3-a8f8-e76f28ba43d7",
            Analytics::class.java,
            Crashes::class.java
        )
    }
}
