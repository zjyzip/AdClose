package com.close.hook.ads.hook.preference;

import android.content.SharedPreferences;
import android.content.Context;
import de.robv.android.xposed.XSharedPreferences;
import java.util.Optional;

public class PreferencesHelper {

    private Optional<SharedPreferences> prefs;
    private Optional<XSharedPreferences> xPrefs;

    // 普通 Android 环境
    public PreferencesHelper(Context context, String prefsName) {
        prefs = Optional.ofNullable(context.getSharedPreferences(prefsName, Context.MODE_WORLD_READABLE));
        xPrefs = Optional.empty();
    }

    // Xposed 环境
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
}
