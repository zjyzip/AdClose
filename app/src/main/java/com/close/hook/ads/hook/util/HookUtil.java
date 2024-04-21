package com.close.hook.ads.hook.util;

import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.function.Consumer;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
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

    public static void hookSingleMethod(ClassLoader classLoader, String className, String methodName, Object returnValue) {
        hookMultipleMethods(classLoader, className, new String[] { methodName }, returnValue);
    }

    public static void hookMultipleMethods(ClassLoader classLoader, String className, String[] methodNames, Object returnValue) {
        Class<?> clazz;
        try {
            clazz = Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
    //      XposedBridge.log("Class not found: " + className + ", " + e);
            return;
        }

        for (Method method : clazz.getDeclaredMethods()) {
            boolean isTargetMethod = false;
            for (String targetMethodName : methodNames) {
                if (method.getName().equals(targetMethodName)) {
                    isTargetMethod = true;
                    break;
                }
            }

            if (isTargetMethod) {
                hookMethodWithReplacement(method, returnValue);
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

    public static void findAndHookMethod(
            Class<?> clazz,
            String methodName,
            String hookType,
            Consumer<XC_MethodHook.MethodHookParam> action,
            Class<?>... parameterTypes) {
        try {
            if (parameterTypes == null || parameterTypes.length == 0) {
                throw new IllegalArgumentException("Parameter types must either be specified as Class or String");
            }

            Object[] methodParams = new Object[parameterTypes.length + 1];
            System.arraycopy(parameterTypes, 0, methodParams, 0, parameterTypes.length);
            methodParams[methodParams.length - 1] = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if ("before".equals(hookType) && action != null) {
                        action.accept(param);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if ("after".equals(hookType) && action != null) {
                        action.accept(param);
                    }
                }
            };

            XposedHelpers.findAndHookMethod(clazz, methodName, methodParams);
        } catch (Throwable e) {
            XposedBridge.log("HookUtil - Error occurred while hooking method " + methodName + " of " + clazz.getSimpleName() + ": " + e.getMessage());
        }
    }

    public static void hookMethod(Method method, String hookType, Consumer<XC_MethodHook.MethodHookParam> action) {
        try {
            XC_MethodHook methodHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if ("before".equals(hookType) && action != null) {
                        action.accept(param);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if ("after".equals(hookType) && action != null) {
                        action.accept(param);
                    }
                }
            };

            XposedBridge.hookMethod(method, methodHook);
        } catch (Throwable e) {
            XposedBridge.log("HookUtil - Error occurred while hooking method " + method.getName() + ": " + e.getMessage());
        }
    }

    public static void hookAllMethods(Class<?> clazz, String methodName, String hookType, Consumer<XC_MethodHook.MethodHookParam> action) {
        try {
            XC_MethodHook methodHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if ("before".equals(hookType) && action != null) {
                        action.accept(param);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if ("after".equals(hookType) && action != null) {
                        action.accept(param);
                    }
                }
            };

            XposedBridge.hookAllMethods(clazz, methodName, methodHook);
        } catch (Throwable e) {
            XposedBridge.log("HookUtil - Error occurred while hooking all methods of " + clazz.getSimpleName() + "." + methodName + ": " + e.getMessage());
        }
    }

    public static void hookAllConstructors(Class<?> clazz, String hookType, Consumer<XC_MethodHook.MethodHookParam> action) {
        try {
            XC_MethodHook methodHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if ("before".equals(hookType) && action != null) {
                        action.accept(param);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if ("after".equals(hookType) && action != null) {
                        action.accept(param);
                    }
                }
            };

            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            for (Constructor<?> constructor : constructors) {
                XposedBridge.hookMethod(constructor, methodHook);
            }
        } catch (Throwable e) {
            XposedBridge.log("HookUtil - Error occurred while hooking all constructors of " + clazz.getSimpleName() + ": " + e.getMessage());
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
