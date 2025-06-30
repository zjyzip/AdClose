package com.close.hook.ads.hook

import com.close.hook.ads.preference.HookPrefs

class SettingsManager(
    private val packageName: String,
    private val prefsHelper: HookPrefs
) {

    private fun key(suffix: String): String = "switch_${suffix}_$packageName"

    val isHandlePlatformAdEnabled: Boolean
        get() = prefsHelper.getBoolean(key("one"), false)

    val isRequestHookEnabled: Boolean
        get() = prefsHelper.getBoolean(key("two"), false)

    val isHideVPNStatusEnabled: Boolean
        get() = prefsHelper.getBoolean(key("three"), false)

    val isDisableFlagSecureEnabled: Boolean
        get() = prefsHelper.getBoolean(key("four"), false)

    val isDisableShakeAdEnabled: Boolean
        get() = prefsHelper.getBoolean(key("five"), false)

    val isHideEnivEnabled: Boolean
        get() = prefsHelper.getBoolean(key("six"), false)

    val isDisableClipboard: Boolean
        get() = prefsHelper.getBoolean(key("seven"), false)

    val isHookTipEnabled: Boolean
        get() = prefsHelper.getBoolean(key("eight"), false)
}
