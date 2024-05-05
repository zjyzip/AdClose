package com.close.hook.ads.hook;

import com.close.hook.ads.hook.preference.PreferencesHelper;

public class SettingsManager {

    private final PreferencesHelper prefsHelper;
    private final String packageName;

    public SettingsManager(PreferencesHelper prefsHelper, String packageName) {
        this.prefsHelper = prefsHelper;
        this.packageName = packageName;
    }

    public boolean isHandlePlatformAdEnabled() {
        return prefsHelper.getBoolean("switch_one_" + packageName, false);
    }

    public boolean isRequestHookEnabled() {
        return prefsHelper.getBoolean("switch_two_" + packageName, false);
    }

    public boolean isHideVPNStatusEnabled() {
        return prefsHelper.getBoolean("switch_three_" + packageName, false);
    }

    public boolean isDisableFlagSecureEnabled() {
        return prefsHelper.getBoolean("switch_four_" + packageName, false);
    }

    public boolean isDisableShakeAdEnabled() {
        return prefsHelper.getBoolean("switch_five_" + packageName, false);
    }

    public boolean isHideEnivEnabled() {
        return prefsHelper.getBoolean("switch_six_" + packageName, false);
    }

    public boolean isDisableClipboard() {
        return prefsHelper.getBoolean("switch_seven_" + packageName, false);
    }
}
