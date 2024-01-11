package com.close.hook.ads;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

import com.close.hook.ads.util.PrefManager;
import com.microsoft.appcenter.AppCenter;
import com.microsoft.appcenter.analytics.Analytics;
import com.microsoft.appcenter.crashes.Crashes;

public class CloseApplication extends Application {

    @SuppressLint("StaticFieldLeak")
    public static Context context;

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();

        AppCenter.start(this, "621cdb49-4473-44d3-a8f8-e76f28ba43d7",
                Analytics.class, Crashes.class);

        AppCompatDelegate.setDefaultNightMode(PrefManager.INSTANCE.getDarkTheme());
    }

}