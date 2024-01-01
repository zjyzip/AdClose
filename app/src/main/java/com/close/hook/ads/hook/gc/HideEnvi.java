package com.close.hook.ads.hook.gc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/*
 * 2023.12.8-10:14
 * 参考 https://d0nuts33.github.io/2023/04/29/加固防护总结
 */

public class HideEnvi {

    private static final Set<String> XPOSED_MAGISK_PATHS = new HashSet<>(Arrays.asList(
            "/sbin/.magisk", "/system/bin/magisk", "/data/data/com.topjohnwu.magisk",
            "/system/lib/libriruloader.so", "/system/bin/su", "/system/xbin/su",
            "/system/sbin/su", "/sbin/su", "/vendor/bin/su"));

    private static Boolean xposedMagiskDetectedInMemory = null;

    public static void handle() {
        try {
            XposedBridge.hookMethod(File.class.getDeclaredMethod("exists"), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    File file = (File) param.thisObject;
                    if (XPOSED_MAGISK_PATHS.contains(file.getAbsolutePath())) {
                        param.setResult(false);
                    }
                }
            });

            XposedBridge.hookAllMethods(Runtime.class, "exec", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (detectXposedOrMagiskInMemory()) {
                        param.setResult(null);
                    }
                }
            });
        } catch (NoSuchMethodException e) {
            XposedBridge.log("HideEnvi Error: " + e.getMessage());
        }
    }

    private static boolean detectXposedOrMagiskInMemory() {
        if (xposedMagiskDetectedInMemory != null) {
            return xposedMagiskDetectedInMemory;
        }

        try {
            xposedMagiskDetectedInMemory = false;
            BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (lineContainsXposedMagiskFeatures(line)) {
                    xposedMagiskDetectedInMemory = true;
                    break;
                }
            }
            reader.close();
        } catch (Exception e) {
            XposedBridge.log("HideEnvi Error: " + e.getMessage());
        }
        return xposedMagiskDetectedInMemory;
    }

    private static boolean lineContainsXposedMagiskFeatures(String line) {
        String[] xposedFeatures = {"xposed.installer", "app_process_xposed", "libriru_", "/data/misc/edxp_",
                "libxposed_art.so", "libriruloader.so", "app_process_zposed",
                "liblspd.so", "libriru_edxp.so"};
        String[] magiskFeatures = {"/.magisk", "MAGISK_INJ_"};

        for (String feature : xposedFeatures) {
            if (line.contains(feature)) {
                return true;
            }
        }
        for (String feature : magiskFeatures) {
            if ((line.contains(feature) && line.contains("r-xp")) || line.length() > 8192) {
                return true;
            }
        }
        return false;
    }
}
