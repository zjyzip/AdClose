package com.close.hook.ads.hook.util;

import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.function.Consumer;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;

public class HookUtil {

	public static class HookInfo {
		public String className;
		public String[] methodNames;
		public Object returnValue;

		public HookInfo(String className, String[] methodNames, Object returnValue) {
			this.className = className;
			this.methodNames = methodNames;
			this.returnValue = returnValue;
		}

		public HookInfo(String className, String methodName, Object returnValue) {
			this(className, new String[] { methodName }, returnValue);
		}
	}

	public static void hookMethod(ClassLoader classLoader, String className, String methodName, Object returnValue) {
		hookMethods(classLoader, className, new String[] { methodName }, returnValue);
	}

	public static void hookMethods(ClassLoader classLoader, String className, String[] methodNames,
			Object returnValue) {
		Class<?> clazz;
		try {
			clazz = Class.forName(className, false, classLoader);
		} catch (ClassNotFoundException e) {
	//		XposedBridge.log("Class not found: " + className + ", " + e);
			return;
		}

		for (Method method : clazz.getDeclaredMethods()) {
			String methodName = method.getName();
			for (String targetMethodName : methodNames) {
				if (methodName.equals(targetMethodName)) {
					XposedBridge.hookMethod(method, new XC_MethodReplacement() {
						@Override
						protected Object replaceHookedMethod(MethodHookParam param) {
							return returnValue;
						}
					});
					break;
				}
			}
		}
	}

	private static void hookMethodWithReplacement(Method method, Object returnValue) {
		XposedBridge.hookMethod(method, new XC_MethodReplacement() {
			@Override
			protected Object replaceHookedMethod(MethodHookParam param) {
				return returnValue;
			}
		});
	}

	public static void hookMethod(Class<?> clazz, String methodName, Consumer<XC_MethodHook.MethodHookParam> action) {
		try {
			XposedBridge.hookAllMethods(clazz, methodName, new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					action.accept(param);
				}
			});
		} catch (Throwable e) {
			XposedBridge.log("HookUtil - Error occurred while hooking " + clazz.getSimpleName() + "." + methodName
					+ ": " + e.getMessage());
		}
	}

	public static void hookAllConstructors(Class<?> clazz, Consumer<XC_MethodHook.MethodHookParam> action) {
		try {
			Constructor<?>[] constructors = clazz.getDeclaredConstructors();
			for (Constructor<?> constructor : constructors) {
				XposedBridge.hookMethod(constructor, new XC_MethodHook() {
					@Override
					protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
						action.accept(param);
					}
				});
			}
		} catch (Throwable e) {
			XposedBridge.log("HookUtil - Error occurred while hooking constructors of " + clazz.getSimpleName() + ": "
					+ e.getMessage());
		}
	}

	public static String getFormattedStackTrace() {
		StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
		StringBuilder sb = new StringBuilder();
		for (StackTraceElement element : stackTrace) {
			sb.append("\tat ").append(element.toString()).append("\n");
		}
		return sb.toString();
	}

}
