package com.close.hook.ads.hook.gc;

import android.app.Dialog;
import android.view.Window;
import android.view.WindowManager;
import com.close.hook.ads.hook.util.HookUtil;

public class DisableFlagSecure {

    public static void process() {
        HookUtil.hookAllMethods(Window.class, "setFlags", "before", param -> handleFlagSecure(param.args));
        HookUtil.hookAllMethods(Window.class, "addFlags", "before", param -> handleFlagSecure(param.args));
        HookUtil.hookAllMethods(Dialog.class, "setFlags", "before", param -> handleFlagSecure(param.args));
        HookUtil.hookAllConstructors(WindowManager.LayoutParams.class, "before", param -> handleFlagSecure(param.args));
    }

    private static void handleFlagSecure(Object[] args) {
        if (args == null || args.length == 0) {
            return;
        }

        if (args.length >= 1 && args[0] instanceof Integer) {
            int flags = (Integer) args[0];
            flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
            args[0] = flags;
        }

        if (args.length >= 2 && args[1] instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams layoutParams = (WindowManager.LayoutParams) args[1];
            layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
        }
    }
}
