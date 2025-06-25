package com.close.hook.ads.hook.gc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Set;

import com.close.hook.ads.hook.util.HookUtil;

import de.robv.android.xposed.XposedBridge;

public class HideEnvi {

    private static final Set<String> XPOSED_MAGISK_PATHS = Set.of(
            "/sbin/.magisk",
            "/system/bin/magisk",
            "//system/bin/magiskpolicy",
            "/data/adb/lspd",
            "/data/adb/modules",
            "/data/adb/magisk",
            "/system/bin/su",
            "/system/xbin/su",
            "/system/sbin/su",
            "/bin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/system/bin/.ext/.su",
            "/su/bin/su"
    );

    private static final Set<String> XPOSED_MEMORY_FEATURES = Set.of(
            "xposed.installer",
            "app_process_xposed",
            "libriru_",
            "/data/misc/edxp_",
            "libxposed_art.so",
            "libriruloader.so",
            "app_process_zposed",
            "liblspd.so",
            "libriru_edxp.so"
    );

    public static void handle() {
        hookFileExistenceChecks();
        hookRuntimeExecutions();
        hookSystemProperties();
        AntiEmulatorDetection.handle();
    }

    private static void hookFileExistenceChecks() {
        HookUtil.hookAllMethods(File.class, "exists", "before", param -> {
            File file = (File) param.thisObject;
            String path = file.getAbsolutePath();
            if (isSensitivePath(path)) {
                XposedBridge.log("HideEnvi: Hiding path existence for: " + path);
                param.setResult(false);
            }
        });
    }

    private static boolean isSensitivePath(String path) {
        return XPOSED_MAGISK_PATHS.stream().anyMatch(path::startsWith);
    }

    private static void hookRuntimeExecutions() {
        HookUtil.hookAllMethods(Runtime.class, "exec", "before", param -> {
            String[] cmdArray = null;
            if (param.args[0] instanceof String[]) {
                cmdArray = (String[]) param.args[0];
            } else if (param.args[0] instanceof String) {
                cmdArray = new String[]{(String) param.args[0]};
            }

            if (cmdArray != null && cmdArray.length > 0) {
                String command = cmdArray[0].toLowerCase();
                if (command.contains("su") || command.contains("which") || command.contains("mount") ||
                    command.contains("getprop") || command.contains("id") || command.contains("busybox")) {
                    if (isXposedMagiskDetectedInMemory()) {
                        param.setResult(null);
                        return;
                    }
                }
            }
        });
    }

    private static boolean isXposedMagiskDetectedInMemory() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (XPOSED_MEMORY_FEATURES.stream().anyMatch(line::contains) ||
                    ((line.contains("/.magisk") || line.contains("MAGISK_INJ_")) && line.contains("r-xp"))) {
                    XposedBridge.log("HideEnvi: Detected Xposed/Magisk features in memory map: " + line);
                    return true;
                }
            }
        } catch (IOException e) {
            XposedBridge.log("HideEnvi Error reading /proc/self/maps: " + e.getMessage());
        }
        return false;
    }

    private static void hookSystemProperties() {
        HookUtil.hookAllMethods("android.os.SystemProperties", "get", "before", param -> {
            String key = (String) param.args[0];
            if (isXposedMagiskProperty(key)) {
                param.setResult("");
            }
        });
    }

    private static boolean isXposedMagiskProperty(String key) {
        return key.toLowerCase().contains("xposed") || key.toLowerCase().contains("magisk");
    }
}
