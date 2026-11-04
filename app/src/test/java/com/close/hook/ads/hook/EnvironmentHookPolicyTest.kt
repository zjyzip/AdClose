package com.close.hook.ads.hook

import com.close.hook.ads.hook.util.EnvironmentHookPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentHookPolicyTest {
    @Test
    fun rootCommandsAreMatchedByTokensInsteadOfSubstrings() {
        assertTrue(EnvironmentHookPolicy.isRootCommand("su -c id"))
        assertTrue(EnvironmentHookPolicy.isRootCommand("/system/xbin/su"))
        assertTrue(EnvironmentHookPolicy.isRootCommand("sh -c 'which su'"))
        assertFalse(EnvironmentHookPolicy.isRootCommand("which ls"))
        assertFalse(EnvironmentHookPolicy.isRootCommand("/system/bin/sum file"))
        assertFalse(EnvironmentHookPolicy.isRootCommand("dumpsys surfaceflinger"))
        assertFalse(EnvironmentHookPolicy.isRootCommand("echo summary"))
    }

    @Test
    fun hidingRootFilesDoesNotHideOrdinarySystemDirectories() {
        assertTrue(EnvironmentHookPolicy.isSensitivePath("/system/bin/su"))
        assertTrue(EnvironmentHookPolicy.isSensitivePath("/data/adb/lspd/config"))
        assertFalse(EnvironmentHookPolicy.isSensitivePath("/system/bin/sum"))
        assertFalse(EnvironmentHookPolicy.isSensitivePath("/dev/urandom"))
        assertFalse(EnvironmentHookPolicy.isSensitivePath("/cache/app-file"))
        assertFalse(EnvironmentHookPolicy.isSensitivePath("/data/local/tmp"))
        assertFalse(EnvironmentHookPolicy.isSensitivePath("/data/adb/lspdocuments"))
    }
}
