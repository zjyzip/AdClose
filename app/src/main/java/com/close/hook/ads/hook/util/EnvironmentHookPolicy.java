package com.close.hook.ads.hook.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Matching rules without intercepting unrelated filesystem paths or command names. */
public final class EnvironmentHookPolicy {
    private EnvironmentHookPolicy() {}

    private static final Set<String> ROOT_BINARIES = new HashSet<>(Arrays.asList(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
            "/system/bin/.ext/.su"
    ));
    private static final String[] ROOT_DIRECTORIES = {
            "/data/adb/magisk", "/data/adb/lspd", "/system/usr/we-need-root"
    };
    private static final Pattern ROOT_COMMAND = Pattern.compile(
            "(^|[\\s/;|&\"'])(su|magisk|resetprop)(?=$|[\\s;|&\"'])"
    );

    public static boolean isSensitivePath(String path) {
        if (path == null) return false;
        if (ROOT_BINARIES.contains(path) || path.contains("magisk")) return true;
        for (String directory : ROOT_DIRECTORIES) {
            if (path.equals(directory) || path.startsWith(directory + "/")) return true;
        }
        return false;
    }

    public static boolean isRootCommand(String command) {
        return command != null && ROOT_COMMAND.matcher(command).find();
    }
}
