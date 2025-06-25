package com.close.hook.ads.hook.gc;

import android.app.Dialog;
import android.view.Window;
import android.view.WindowManager;
import com.close.hook.ads.hook.util.HookUtil;
import de.robv.android.xposed.XposedBridge;

public class DisableFlagSecure {

    public static void process() {
        HookUtil.hookAllMethods(Window.class, "setFlags", "before", param -> handleWindowOrDialogFlags(param.args));
        HookUtil.hookAllMethods(Window.class, "addFlags", "before", param -> handleWindowOrDialogFlags(param.args));
        HookUtil.hookAllMethods(Dialog.class, "setFlags", "before", param -> handleWindowOrDialogFlags(param.args));
        HookUtil.hookAllConstructors(WindowManager.LayoutParams.class, "before", param -> handleLayoutParamsConstructor(param.args));
    }

    private static void handleWindowOrDialogFlags(Object[] args) {
        if (args != null && args.length > 0 && args[0] instanceof Integer) {
            int currentFlags = (int) args[0];
            if ((currentFlags & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                args[0] = currentFlags & ~WindowManager.LayoutParams.FLAG_SECURE;
                XposedBridge.log("DisableFlagSecure: Removed FLAG_SECURE from Window/Dialog setFlags/addFlags. Original: " + Integer.toHexString(currentFlags) + ", New: " + Integer.toHexString((int) args[0]));
            }
        }
    }

    private static void handleLayoutParamsConstructor(Object[] args) {
        if (args != null && args.length > 0) {
            if (args[0] instanceof Integer) {
                int currentFlags = (int) args[0];
                if ((currentFlags & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                    args[0] = currentFlags & ~WindowManager.LayoutParams.FLAG_SECURE;
                    XposedBridge.log("DisableFlagSecure: Removed FLAG_SECURE from LayoutParams constructor (arg[0]). Original: " + Integer.toHexString(currentFlags) + ", New: " + Integer.toHexString((int) args[0]));
                }
            }
        }
    }
}
