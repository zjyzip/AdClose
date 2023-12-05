package com.close.hook.ads;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;

public class CloseApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        DynamicColors.applyToActivitiesIfAvailable(this);
    }

}