package com.close.hook.ads.hook.gc;

import com.close.hook.ads.hook.util.HookUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/*
 * 2023.12.8-10:14
 * 参考 https://d0nuts33.github.io/2023/04/29/加固防护总结
 */

public class HideEnvi {

    private static final Set<String> XPOSED_MAGISK_PATHS = new HashSet<>(Arrays.asList(
            "/sbin/.magisk",
            "/system/bin/magisk",
            "/data/data/com.topjohnwu.magisk",
            "/system/lib/libriruloader.so",
            "/system/bin/su",
            "/system/xbin/su",
            "/system/sbin/su",
            "/sbin/su",
            "/vendor/bin/su"
        ));

    private static final String[] XPOSED_FEATURES = {
            "xposed.installer",
            "app_process_xposed",
            "libriru_",
            "/data/misc/edxp_",
            "libxposed_art.so",
            "libriruloader.so",
            "app_process_zposed",
            "liblspd.so",
            "libriru_edxp.so"
    };
    
    private static final String[] MAGISK_FEATURES = {"/.magisk", "MAGISK_INJ_"};

    public static void handle() {
        hideXposedMagiskPaths();
        hideXposedMagiskInExec();
    }

    private static void hideXposedMagiskPaths() {
        HookUtil.hookAllMethods(File.class, "exists", param -> {
            File file = (File) param.thisObject;
            if (XPOSED_MAGISK_PATHS.contains(file.getAbsolutePath())) {
                param.setResult(false);
            }
        });
    }

    private static void hideXposedMagiskInExec() {
        HookUtil.hookAllMethods(Runtime.class, "exec", param -> {
            if (detectXposedOrMagiskInMemory()) {
                param.setResult(null);
            }
        });
    }

    private static boolean detectXposedOrMagiskInMemory() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lineContainsXposedMagiskFeatures(line)) {
                    return true;
                }
            }
        } catch (Exception e) {
            XposedBridge.log("HideEnvi Error: " + e.getMessage());
        }
        return false;
    }

    private static boolean lineContainsXposedMagiskFeatures(String line) {
        for (String feature : XPOSED_FEATURES) {
            if (line.contains(feature)) {
                return true;
            }
        }
        for (String feature : MAGISK_FEATURES) {
            if ((line.contains(feature) && line.contains("r-xp")) || line.length() > 8192) {
                return true;
            }
        }
        return false;
    }
}
