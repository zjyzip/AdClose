package com.close.hook.ads;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

import com.close.hook.ads.util.PrefManager;
import com.google.android.material.color.DynamicColors;

public class CloseApplication extends Application {

    @SuppressLint("StaticFieldLeak")
    public static Context context;

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
        AppCompatDelegate.setDefaultNightMode(PrefManager.INSTANCE.getDarkTheme());
        //AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        //DynamicColors.applyToActivitiesIfAvailable(this);
    }

}