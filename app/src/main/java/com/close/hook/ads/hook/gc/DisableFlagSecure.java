package com.close.hook.ads.hook.gc;

import android.app.Dialog;
import android.view.Window;
import android.view.WindowManager;
import com.close.hook.ads.hook.util.HookUtil;

import de.robv.android.xposed.XC_MethodHook;

public class DisableFlagSecure {

    public static void process() {
        HookUtil.hookAllMethods(Window.class, "setFlags", "before", param -> handleFlagSecure(param));
        HookUtil.hookAllMethods(Window.class, "addFlags", "before", param -> handleFlagSecure(param));
        HookUtil.hookAllMethods(Dialog.class, "setFlags", "before", param -> handleFlagSecure(param));
        HookUtil.hookAllConstructors(WindowManager.LayoutParams.class, "before", param -> handleFlagSecure(param));
    }

    private static void handleFlagSecure(XC_MethodHook.MethodHookParam param) {
        if (param.args == null) {
            return;
        }

        if (param.args.length >= 1 && param.args[0] instanceof Integer) {
            int flags = (Integer) param.args[0];
            flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
            param.args[0] = flags;
        }

        if (param.args.length >= 2 && param.args[1] instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams layoutParams = (WindowManager.LayoutParams) param.args[1];
            layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
        }
    }
}
