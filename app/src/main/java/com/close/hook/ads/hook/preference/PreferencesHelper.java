package com.close.hook.ads.hook.preference;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Optional;

import de.robv.android.xposed.XSharedPreferences;

public class PreferencesHelper {

    private static final String PREF_NAME = "com.close.hook.ads_preferences";

    private final Optional<SharedPreferences> prefs;
    private final Optional<XSharedPreferences> xPrefs;

/*
    public PreferencesHelper(Context context) {
        prefs = Optional.of(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE));
        xPrefs = Optional.empty();

        try {
            File prefFile = new File("/data/data/" + context.getPackageName() + "/shared_prefs/" + PREF_NAME + ".xml");
            if (prefFile.exists()) {
                prefFile.setReadable(true, false);
            }
        } catch (Exception ignored) {
        }
    }
*/

    public PreferencesHelper(Context context) {
        prefs = Optional.of(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE));
        xPrefs = Optional.empty();
    }

    public PreferencesHelper() {
        xPrefs = Optional.of(new XSharedPreferences("com.close.hook.ads", PREF_NAME));
        prefs = Optional.empty();
        xPrefs.ifPresent(xp -> {
            xp.makeWorldReadable();
            xp.reload();
        });
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        if (prefs.isPresent()) {
            return prefs.get().getBoolean(key, defaultValue);
        } else if (xPrefs.isPresent()) {
            xPrefs.get().reload();
            return xPrefs.get().getBoolean(key, defaultValue);
        }
        return defaultValue;
    }

    public void setBoolean(String key, boolean value) {
        prefs.ifPresent(p -> p.edit().putBoolean(key, value).apply());
    }

    public int getInt(String key, int defaultValue) {
        if (prefs.isPresent()) {
            return prefs.get().getInt(key, defaultValue);
        } else if (xPrefs.isPresent()) {
            xPrefs.get().reload();
            return xPrefs.get().getInt(key, defaultValue);
        }
        return defaultValue;
    }

    public void setInt(String key, int value) {
        prefs.ifPresent(p -> p.edit().putInt(key, value).apply());
    }

    public long getLong(String key, long defaultValue) {
        if (prefs.isPresent()) {
            return prefs.get().getLong(key, defaultValue);
        } else if (xPrefs.isPresent()) {
            xPrefs.get().reload();
            return xPrefs.get().getLong(key, defaultValue);
        }
        return defaultValue;
    }

    public void putLong(String key, long value) {
        prefs.ifPresent(p -> p.edit().putLong(key, value).apply());
    }

    public float getFloat(String key, float defaultValue) {
        if (prefs.isPresent()) {
            return prefs.get().getFloat(key, defaultValue);
        } else if (xPrefs.isPresent()) {
            xPrefs.get().reload();
            return xPrefs.get().getFloat(key, defaultValue);
        }
        return defaultValue;
    }

    public void setFloat(String key, float value) {
        prefs.ifPresent(p -> p.edit().putFloat(key, value).apply());
    }

    public String getString(String key, String defaultValue) {
        if (prefs.isPresent()) {
            return prefs.get().getString(key, defaultValue);
        } else if (xPrefs.isPresent()) {
            xPrefs.get().reload();
            return xPrefs.get().getString(key, defaultValue);
        }
        return defaultValue;
    }

    public void setString(String key, String value) {
        prefs.ifPresent(p -> p.edit().putString(key, value).apply());
    }

    public boolean contains(String key) {
        if (prefs.isPresent()) {
            return prefs.get().contains(key);
        } else if (xPrefs.isPresent()) {
            xPrefs.get().reload();
            return xPrefs.get().contains(key);
        }
        return false;
    }

    public void remove(String key) {
        prefs.ifPresent(p -> p.edit().remove(key).apply());
    }

    public void clear() {
        prefs.ifPresent(p -> p.edit().clear().apply());
    }
}
