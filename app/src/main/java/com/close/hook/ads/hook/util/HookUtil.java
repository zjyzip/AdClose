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

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        XposedHelpers.setStaticObjectField(clazz, fieldName, value);
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        XposedHelpers.setIntField(obj, fieldName, value);
    }

    private static Class<?> getClass(Object clazz) throws ClassNotFoundException {
        if (clazz instanceof Class<?>) {
            return (Class<?>) clazz;
        } else if (clazz instanceof String) {
            return Class.forName((String) clazz);
        } else {
            throw new IllegalArgumentException("Class parameter must be a Class<?> instance or a full class name string");
        }
    }

    public static void findAndHookMethod(
            Object clazz,
            String methodName,
            String hookType,
            Consumer<XC_MethodHook.MethodHookParam> action,
            Object... parameterTypes) {
        try {
            Class<?> actualClass = getClass(clazz);

            Class<?>[] classParams = new Class<?>[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                classParams[i] = getClass(parameterTypes[i]);
            }

            Object[] methodParams = new Object[classParams.length + 1];
            System.arraycopy(classParams, 0, methodParams, 0, classParams.length);
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

            XposedHelpers.findAndHookMethod(actualClass, methodName, methodParams);
        } catch (Throwable e) {
            XposedBridge.log("HookUtil - Error occurred while hooking method " + methodName + ": " + e.getMessage());
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

    public static void hookAllMethods(Object clazz, String methodName, String hookType, Consumer<XC_MethodHook.MethodHookParam> action) {
        try {
            Class<?> actualClass = getClass(clazz);
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

            XposedBridge.hookAllMethods(actualClass, methodName, methodHook);
        } catch (Throwable e) {
            XposedBridge.log("HookUtil - Error occurred while hooking all methods of " + methodName + ": " + e.getMessage());
        }
    }

    public static void hookAllConstructors(Object clazz, String hookType, Consumer<XC_MethodHook.MethodHookParam> action) {
        try {
            Class<?> actualClass = getClass(clazz);
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

            Constructor<?>[] constructors = actualClass.getDeclaredConstructors();
            for (Constructor<?> constructor : constructors) {
                XposedBridge.hookMethod(constructor, methodHook);
            }
        } catch (Throwable e) {
            XposedBridge.log("HookUtil - Error occurred while hooking all constructors: " + e.getMessage());
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
