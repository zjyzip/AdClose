package com.close.hook.ads.hook.gc;

import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.close.hook.ads.hook.util.HookUtil;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class HideEnvi {

    private static final String TAG = "HideEnvi";

    private static final Set<String> SENSITIVE_PATHS = new HashSet<>(Arrays.asList(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
            "/system/bin/.ext/.su", "/data/adb/magisk", "/data/adb/lspd",
            "/system/usr/we-need-root/", "/cache/", "/data/local/", "/dev/"
    ));

    private static final Set<String> MEMORY_FEATURES = Set.of(
            "xposed.installer", "app_process_xposed", "libriru_", "liblspd.so",
            "libriruloader.so", "libxposed_art.so", "magisk"
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
                param.setResult(false);
            }
        });
    }

    private static boolean isSensitivePath(String path) {
        return SENSITIVE_PATHS.stream().anyMatch(path::contains) || path.contains("magisk");
    }

    private static void hookRuntimeExecutions() {
        HookUtil.hookAllMethods(Runtime.class, "exec", "before", param -> {
            Object arg = param.args[0];
            String command = "";
            if (arg instanceof String) {
                command = (String) arg;
            } else if (arg instanceof String[]) {
                command = String.join(" ", (String[]) arg);
            }

            if (command.isEmpty()) return;

            if (command.contains("su") || command.contains("which")) {
                param.args[0] = new String[]{"sh", "-c", "exit 1"};
                return;
            }

            if (command.contains("getprop")) {
                param.setResult(createFakeProcess("[ro.debuggable]: [0]\n[ro.secure]: [1]\n[ro.build.tags]: [release-keys]"));
            }
        });
    }

    private static Process createFakeProcess(final String output) {
        return new Process() {
            @Override
            public OutputStream getOutputStream() {
                return null;
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(output.getBytes());
            }

            @Override
            public InputStream getErrorStream() {
                return new ByteArrayInputStream(new byte[0]);
            }

            @Override
            public int waitFor() {
                return 0;
            }

            @Override
            public int exitValue() {
                return 0;
            }

            @Override
            public void destroy() {}
        };
    }

    private static void hookSystemProperties() {
        HookUtil.hookAllMethods("android.os.SystemProperties", "get", "before", param -> {
            String key = (String) param.args[0];
            if ("ro.debuggable".equals(key)) {
                param.setResult("0");
            } else if ("ro.secure".equals(key)) {
                param.setResult("1");
            } else if (key.toLowerCase().contains("magisk") || key.toLowerCase().contains("xposed")) {
                param.setResult("");
            }
        });

        HookUtil.hookAllMethods("android.os.SystemProperties", "getInt", "before", param -> {
            String key = (String) param.args[0];
            if ("ro.debuggable".equals(key)) param.setResult(0);
            if ("ro.secure".equals(key)) param.setResult(1);
        });

        HookUtil.hookAllMethods("android.os.SystemProperties", "getBoolean", "before", param -> {
            String key = (String) param.args[0];
            if ("ro.debuggable".equals(key)) param.setResult(false);
            if ("ro.secure".equals(key)) param.setResult(true);
        });
    }
}
