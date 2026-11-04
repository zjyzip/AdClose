package com.close.hook.ads.hook.common;

import android.view.Window;
import android.view.WindowManager;
import com.close.hook.ads.hook.util.HookUtil;
import de.robv.android.xposed.XposedBridge;

public class DisableFlagSecure {
    public static void process() {
        HookUtil.hookAllMethods(Window.class, "setFlags", "before", param -> stripFlagArgument(param.args));
        HookUtil.hookAllMethods(Window.class, "addFlags", "before", param -> stripFlagArgument(param.args));
        HookUtil.hookAllMethods(Window.class, "setAttributes", "before", param -> {
            if (param.args.length > 0 && param.args[0] instanceof WindowManager.LayoutParams) {
                stripLayoutFlags((WindowManager.LayoutParams) param.args[0]);
            }
        });
        // Constructor overloads have different argument positions. Inspect the initialized object.
        HookUtil.hookAllConstructors(WindowManager.LayoutParams.class, "after", param -> {
            if (param.thisObject instanceof WindowManager.LayoutParams) {
                stripLayoutFlags((WindowManager.LayoutParams) param.thisObject);
            }
        });
    }

    private static void stripFlagArgument(Object[] args) {
        if (args != null && args.length > 0 && args[0] instanceof Integer) {
            args[0] = (int) args[0] & ~WindowManager.LayoutParams.FLAG_SECURE;
        }
    }

    private static void stripLayoutFlags(WindowManager.LayoutParams params) {
        if ((params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
            params.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
            XposedBridge.log("DisableFlagSecure: Removed FLAG_SECURE from LayoutParams.flags");
        }
    }
}
