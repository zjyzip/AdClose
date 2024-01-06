package com.close.hook.ads.hook.gc;

import java.io.File;
import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/*
 * 2023.12.8-10:14
 * 参考 https://d0nuts33.github.io/2023/04/29/加固防护总结
 */

public class HideEnvi {

	public static void handle() {
		try {
			XposedBridge.hookMethod(File.class.getDeclaredMethod("exists"), new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					String path = ((File) param.thisObject).getAbsolutePath();
					if (isXposedOrMagiskPath(path) || isLibRiruloaderSo(path)) {
						param.setResult(false);
					}
				}

				private boolean isXposedOrMagiskPath(String path) {
					// Magisk & Xposed paths
					String[] paths = { "/sbin/.magisk", "/system/bin/magisk", "/data/data/com.topjohnwu.magisk",
							"/system/lib/libriruloader.so", "/system/bin/su", "/system/xbin/su", "/system/sbin/su",
							"/sbin/su", "/vendor/bin/su", "xposed.installer", "app_process_xposed", "libriru_",
							"/data/misc/edxp_", "libxposed_art.so", "libriruloader.so", "app_process_zposed" };
					return Arrays.asList(paths).contains(path);
				}

				private boolean isLibRiruloaderSo(String path) {
					return "/system/lib/libriruloader.so".equals(path);
				}
			});
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}
}
