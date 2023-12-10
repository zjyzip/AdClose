package com.close.hook.ads;

import android.annotation.SuppressLint;
import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;

public class CloseApplication extends Application {

    @SuppressLint("StaticFieldLeak")

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        DynamicColors.applyToActivitiesIfAvailable(this);
    }

}