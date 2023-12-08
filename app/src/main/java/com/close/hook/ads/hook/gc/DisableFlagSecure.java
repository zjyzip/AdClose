package com.close.hook.ads.hook.gc;

import android.app.Dialog;
import android.view.Window;
import android.view.WindowManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public class DisableFlagSecure {

    private static final XC_MethodHook generalHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            if (param.args == null) {
                return;
            }

            if (param.args.length >= 1 && param.args[0] instanceof Integer) {
                int flags = (Integer) param.args[0];
                flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                param.args[0] = flags;
            }

            if (param.args.length >= 2 && param.args[1] instanceof WindowManager.LayoutParams layoutParams) {
                layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
            }
        }
    };

    public static void process() {
        hookMethods();
    }

    private static void hookMethods() {
        XposedBridge.hookAllMethods(Window.class, "setFlags", generalHook);
        XposedBridge.hookAllMethods(Window.class, "addFlags", generalHook);
        XposedBridge.hookAllMethods(Dialog.class, "setFlags", generalHook);
        XposedBridge.hookAllConstructors(WindowManager.LayoutParams.class, generalHook);
    }
}
