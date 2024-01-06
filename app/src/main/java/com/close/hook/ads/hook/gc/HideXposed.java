package com.close.hook.ads.hook.gc;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HideXposed {
	private static final String XPOSED = "xposed";
	private static final String SD_CARD_PATH = Environment.getExternalStorageDirectory().getPath();
	private static final String PROC_MAPS_PATH = "/proc/[0-9]+/maps";

	public static void handle(XC_LoadPackage.LoadPackageParam lpparam) {
		hookClassLoader();
		hookFileConstructor();
		hookStackTraceElements();
		hookPackageManagerMethods(lpparam);
		hookFileList();
		hookSystemGetenv();
	}

	private static void hookClassLoader() {
		XC_MethodHook classLoaderHook = new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				String className = (String) param.args[0];
				if (className.startsWith("de.robv.android.xposed.")) {
					param.setThrowable(new ClassNotFoundException(className));
				}
			}
		};
		XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class, classLoaderHook);
		XposedHelpers.findAndHookMethod(Class.class, "forName", String.class, boolean.class, ClassLoader.class,
				classLoaderHook);
	}

	private static void hookFileConstructor() {
		XC_MethodHook fileConstructorHook = new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				String path = (String) param.args[0];
				if (path.matches(PROC_MAPS_PATH) || path.toLowerCase().contains(XPOSED)) {
					param.args[0] = SD_CARD_PATH + "/.nomedia";
				}
			}
		};
		XposedHelpers.findAndHookConstructor(File.class, String.class, fileConstructorHook);
	}

	private static void hookStackTraceElements() {
		XC_MethodHook stackTraceHook = new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				StackTraceElement[] elements = (StackTraceElement[]) param.getResult();
				ArrayList<StackTraceElement> filtered = new ArrayList<>(elements.length);
				for (StackTraceElement element : elements) {
					if (!element.getClassName().startsWith("de.robv.android.xposed.")) {
						filtered.add(element);
					}
				}
				param.setResult(filtered.toArray(new StackTraceElement[0]));
			}
		};
		XposedHelpers.findAndHookMethod(Throwable.class, "getStackTrace", stackTraceHook);
		XposedHelpers.findAndHookMethod(Thread.class, "getStackTrace", stackTraceHook);
	}

	private static void hookPackageManagerMethods(XC_LoadPackage.LoadPackageParam lpparam) {
		XC_MethodHook packageManagerHook = new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				List<?> originalList = (List<?>) param.getResult();
				List<Object> filteredList = new ArrayList<>();
				for (Object info : originalList) {
					String packageName = info instanceof PackageInfo ? ((PackageInfo) info).packageName
							: ((ApplicationInfo) info).packageName;
					if (!packageName.toLowerCase().contains(XPOSED)) {
						filteredList.add(info);
					}
				}
				param.setResult(filteredList);
			}
		};
		XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", lpparam.classLoader,
				"getInstalledPackages", int.class, packageManagerHook);
		XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", lpparam.classLoader,
				"getInstalledApplications", int.class, packageManagerHook);
	}

	private static void hookFileList() {
		XC_MethodHook fileListHook = new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				String[] fileList = (String[]) param.getResult();
				if (fileList == null)
					return;
				List<String> filteredList = new ArrayList<>();
				for (String file : fileList) {
					if (!file.toLowerCase().contains(XPOSED) && !file.equals("su")) {
						filteredList.add(file);
					}
				}
				param.setResult(filteredList.toArray(new String[0]));
			}
		};
		XposedHelpers.findAndHookMethod(File.class, "list", fileListHook);
	}

	private static void hookSystemGetenv() {
		XC_MethodHook getenvHook = new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				if (param.args.length == 0) {
					Map<String, String> res = (Map<String, String>) param.getResult();
					if (res != null) {
						res.put("CLASSPATH", filterClasspath(res.get("CLASSPATH")));
					}
				} else if ("CLASSPATH".equals(param.args[0])) {
					param.setResult(filterClasspath((String) param.getResult()));
				}
			}

			private String filterClasspath(String classpath) {
				if (classpath == null) {
					return null;
				}
				String[] paths = classpath.split(":");
				StringBuilder filteredClasspath = new StringBuilder();
				for (String path : paths) {
					if (!path.toLowerCase().contains(XPOSED)) {
						if (filteredClasspath.length() > 0) {
							filteredClasspath.append(":");
						}
						filteredClasspath.append(path);
					}
				}
				return filteredClasspath.toString();
			}
		};
		XposedHelpers.findAndHookMethod(System.class, "getenv", String.class, getenvHook);
	}

}
