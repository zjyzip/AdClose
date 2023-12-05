package com.close.hook.ads.hook.gc;

import java.io.File;
import java.util.Arrays;

import de.robv.android.xposed.*;

public class HideEnvi {

	public static void handle() {

        try {
            XposedBridge.hookMethod(File.class.getDeclaredMethod("exists"), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String path = ((File) param.thisObject).getAbsolutePath();
                    if (Arrays.asList(new String[]{"/system/bin/su", "/system/xbin/su", "/system/sbin/su", "/sbin/su", "/vendor/bin/su"}).contains(path)) {
                        param.setResult(false);
                    }

                }
            });
            XposedBridge.hookAllMethods(Runtime.class, "exec", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object arg = param.args[0];
                    if (arg instanceof String[]) {
                        boolean z = Arrays.toString(((String[]) arg)).contains("/system/xbin/which");
                        if (z) {
                            param.args[0] = new String[]{"/system/xbin/which", "lovejiuwu"};
                        }
                    } else if (arg instanceof String) {
                        if (arg.equals("su")) {
                            param.args[0] = "";
                        }
                    }


                }
            });
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
		
	}

}
