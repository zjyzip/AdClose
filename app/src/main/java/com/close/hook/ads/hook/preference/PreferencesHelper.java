package com.close.hook.ads.hook.preference;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Optional;

import de.robv.android.xposed.XSharedPreferences;

public class PreferencesHelper {

    private final Optional<SharedPreferences> prefs;
    private final Optional<XSharedPreferences> xPrefs;

    public PreferencesHelper(Context context, String prefsName) {
        prefs = Optional.ofNullable(context.getSharedPreferences(prefsName, Context.MODE_WORLD_READABLE));
        xPrefs = Optional.empty();
    }

    public PreferencesHelper(String packageName, String prefsName) {
        xPrefs = Optional.ofNullable(new XSharedPreferences(packageName, prefsName));
        prefs = Optional.empty();
        xPrefs.ifPresent(xp -> {
            xp.makeWorldReadable();
            xp.reload();
        });
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return prefs.map(p -> p.getBoolean(key, defaultValue))
                .orElseGet(() -> xPrefs.map(xp -> xp.getBoolean(key, defaultValue))
                        .orElse(defaultValue));
    }

    public void setBoolean(String key, boolean value) {
        prefs.ifPresent(p -> p.edit().putBoolean(key, value).apply());
    }

    public int getInt(String key, int defaultValue) {
        return prefs.map(p -> p.getInt(key, defaultValue))
                .orElseGet(() -> xPrefs.map(xp -> xp.getInt(key, defaultValue))
                        .orElse(defaultValue));
    }

    public void setInt(String key, int value) {
        prefs.ifPresent(p -> p.edit().putInt(key, value).apply());
    }

    public long getLong(String key, long defaultValue) {
        return prefs.map(p -> p.getLong(key, defaultValue))
                .orElseGet(() -> xPrefs.map(xp -> xp.getLong(key, defaultValue))
                        .orElse(defaultValue));
    }

    public void putLong(String key, long value) {
        prefs.ifPresent(p -> p.edit().putLong(key, value).apply());
    }

    public float getFloat(String key, float defaultValue) {
        return prefs.map(p -> p.getFloat(key, defaultValue))
                .orElseGet(() -> xPrefs.map(xp -> xp.getFloat(key, defaultValue))
                        .orElse(defaultValue));
    }

    public void setFloat(String key, float value) {
        prefs.ifPresent(p -> p.edit().putFloat(key, value).apply());
    }

    public String getString(String key, String defaultValue) {
        return prefs.map(p -> p.getString(key, defaultValue))
                .orElseGet(() -> xPrefs.map(xp -> xp.getString(key, defaultValue))
                        .orElse(defaultValue));
    }

    public void setString(String key, String value) {
        prefs.ifPresent(p -> p.edit().putString(key, value).apply());
    }

    public boolean contains(String key) {
        return prefs.map(p -> p.contains(key))
                .orElseGet(() -> xPrefs.map(xp -> xp.contains(key))
                        .orElse(false));
    }

    public void remove(String key) {
        prefs.ifPresent(p -> p.edit().remove(key).apply());
    }

    public void clear() {
        prefs.ifPresent(p -> p.edit().clear().apply());
    }
}
