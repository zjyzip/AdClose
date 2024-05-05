package com.close.hook.ads.hook.gc;

import android.content.ClipboardManager;
import android.hardware.SensorManager;

import com.close.hook.ads.hook.util.HookUtil;

public class DisableClipboard {

    public static void handle() {
        HookUtil.hookAllMethods(ClipboardManager.class, "getPrimaryClip", "before", param -> {
            param.setResult(null);
        });

        HookUtil.hookAllMethods(ClipboardManager.class, "setPrimaryClip", "before", param -> {
            param.setResult(null);
        });
    }
}
