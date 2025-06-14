package com.close.hook.ads.hook

import com.close.hook.ads.hook.preference.PreferencesHelper

class SettingsManager(
    private val prefsHelper: PreferencesHelper,
    private val packageName: String
) {

    val isHandlePlatformAdEnabled: Boolean
        get() = prefsHelper.getBoolean("switch_one_$packageName", false)

    val isRequestHookEnabled: Boolean
        get() = prefsHelper.getBoolean("switch_two_$packageName", false)

    val isHideVPNStatusEnabled: Boolean
        get() = prefsHelper.getBoolean("switch_three_$packageName", false)

    val isDisableFlagSecureEnabled: Boolean
        get() = prefsHelper.getBoolean("switch_four_$packageName", false)

    val isDisableShakeAdEnabled: Boolean
        get() = prefsHelper.getBoolean("switch_five_$packageName", false)

    val isHideEnivEnabled: Boolean
        get() = prefsHelper.getBoolean("switch_six_$packageName", false)

    val isDisableClipboard: Boolean
        get() = prefsHelper.getBoolean("switch_seven_$packageName", false)

    val isHookTipEnabled: Boolean
        get() = prefsHelper.getBoolean("switch_eight_$packageName", false)
}
