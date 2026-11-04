package com.close.hook.ads.hook.common;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.close.hook.ads.hook.util.EnvironmentHookPolicy;
import com.close.hook.ads.hook.util.HookUtil;

public class HideEnvi {

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
        return EnvironmentHookPolicy.isSensitivePath(path);
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

            if (EnvironmentHookPolicy.isRootCommand(command)) {
                // Returning a failed process preserves every exec overload's declared argument types.
                param.setResult(createFakeProcess("", 1));
                return;
            }

            if (command.contains("getprop")) {
                param.setResult(createFakeProcess("[ro.debuggable]: [0]\n[ro.secure]: [1]\n[ro.build.tags]: [release-keys]", 0));
            }
        });
    }

    private static Process createFakeProcess(final String output, final int exitCode) {
        final InputStream stdout = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        final InputStream stderr = new ByteArrayInputStream(new byte[0]);
        final OutputStream stdin = new OutputStream() {
            @Override public void write(int value) {}
        };
        return new Process() {
            @Override
            public OutputStream getOutputStream() {
                return stdin;
            }

            @Override
            public InputStream getInputStream() {
                return stdout;
            }

            @Override
            public InputStream getErrorStream() {
                return stderr;
            }

            @Override
            public int waitFor() {
                return exitCode;
            }

            @Override
            public int exitValue() {
                return exitCode;
            }

            @Override
            public void destroy() {}
        };
    }

    private static void hookSystemProperties() {
        HookUtil.hookAllMethods("android.os.SystemProperties", "get", "before", param -> {
            String key = (String) param.args[0];
            if (key == null) return;
            if ("ro.debuggable".equals(key)) {
                param.setResult("0");
            } else if ("ro.secure".equals(key)) {
                param.setResult("1");
            } else if (key.toLowerCase(Locale.ROOT).contains("magisk") || key.toLowerCase(Locale.ROOT).contains("xposed")) {
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
