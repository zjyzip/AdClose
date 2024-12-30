package com.close.hook.ads.hook.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Consumer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

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
            this(className, new String[]{methodName}, returnValue);
        }
    }

    public static void hookSingleMethod(ClassLoader classLoader, String className, String methodName, Object returnValue) {
        hookMultipleMethods(classLoader, className, new String[]{methodName}, returnValue);
    }

    public static void hookMultipleMethods(ClassLoader classLoader, String className, String[] methodNames, Object returnValue) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
        if (clazz == null) {
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
            Object clazz,
            String methodName,
            Object[] parameterTypes,
            String hookType,
            Consumer<XC_MethodHook.MethodHookParam> action) {
        findAndHookMethod(clazz, methodName, parameterTypes, hookType, action, null);
    }

    public static void findAndHookMethod(
            Object clazz,
            String methodName,
            Object[] parameterTypes,
            String hookType,
            Consumer<XC_MethodHook.MethodHookParam> action,
            ClassLoader classLoader) {

        try {
            Class<?> actualClass = getClass(clazz, classLoader);
            if (actualClass == null) {
                XposedBridge.log("findAndHookMethod - Class not found: " + clazz);
                return;
            }

            Class<?>[] classParams = new Class<?>[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                classParams[i] = getClass(parameterTypes[i], classLoader);
                if (classParams[i] == null) {
                    XposedBridge.log("findAndHookMethod - Parameter type class not found: " + parameterTypes[i]);
                    return;
                }
            }

            Method method = actualClass.getDeclaredMethod(methodName, classParams);
            if (method == null) {
                XposedBridge.log("findAndHookMethod - Method not found: " + methodName + " in class " + actualClass.getName());
                return;
            }

            XC_MethodHook methodHook = createMethodHook(hookType, action);
            XposedBridge.hookMethod(method, methodHook);
        } catch (NoSuchMethodException e) {
            XposedBridge.log("findAndHookMethod - No such method: " + methodName + ", " + e);
        } catch (Exception e) {
            XposedBridge.log("findAndHookMethod - Error while hooking method: " + methodName + ", " + e);
        }
    }

    private static Class<?> getClass(Object clazz, ClassLoader classLoader) {
        String className = clazz instanceof String ? (String) clazz : ((Class<?>) clazz).getName();
        return XposedHelpers.findClassIfExists(className, classLoader);
    }

    public static void hookMethod(Method method, String hookType, Consumer<XC_MethodHook.MethodHookParam> action) {
        if (method == null) {
            XposedBridge.log("hookMethod - Method is null");
            return;
        }

        try {
            XC_MethodHook methodHook = createMethodHook(hookType, action);
            XposedBridge.hookMethod(method, methodHook);
        } catch (Exception e) {
            XposedBridge.log("hookMethod - Error occurred while hooking method " + method.getName() + ": " + e.getMessage());
        }
    }

    public static void hookAllMethods(Object clazz, String methodName, String hookType, Consumer<XC_MethodHook.MethodHookParam> action) {
        hookAllMethods(clazz, methodName, hookType, action, null);
    }

    public static void hookAllMethods(Object clazz, String methodName, String hookType, Consumer<XC_MethodHook.MethodHookParam> action, ClassLoader classLoader) {
        try {
            Class<?> actualClass = getClass(clazz, classLoader);
            if (actualClass == null) {
                XposedBridge.log("hookAllMethods - Class not found: " + clazz);
                return;
            }

            XC_MethodHook methodHook = createMethodHook(hookType, action);
            XposedBridge.hookAllMethods(actualClass, methodName, methodHook);
        } catch (Throwable e) {
            XposedBridge.log("hookAllMethods - Error occurred while hooking all methods of " + methodName + ": " + e.getMessage());
        }
    }

    public static void hookAllConstructors(Object clazz, String hookType, Consumer<XC_MethodHook.MethodHookParam> action) {
        hookAllConstructors(clazz, hookType, action, null);
    }

    public static void hookAllConstructors(Object clazz, String hookType, Consumer<XC_MethodHook.MethodHookParam> action, ClassLoader classLoader) {
        try {
            Class<?> actualClass = getClass(clazz, classLoader);
            if (actualClass == null) {
                XposedBridge.log("hookAllConstructors - Class not found: " + clazz);
                return;
            }

            XC_MethodHook methodHook = createMethodHook(hookType, action);
            Constructor<?>[] constructors = actualClass.getDeclaredConstructors();
            for (Constructor<?> constructor : constructors) {
                XposedBridge.hookMethod(constructor, methodHook);
            }
        } catch (Throwable e) {
            XposedBridge.log("hookAllConstructors - Error occurred while hooking all constructors: " + e.getMessage());
        }
    }

    private static XC_MethodHook createMethodHook(String hookType, Consumer<XC_MethodHook.MethodHookParam> action) {
        return new XC_MethodHook() {
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
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        XposedHelpers.setStaticObjectField(clazz, fieldName, value);
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        XposedHelpers.setIntField(obj, fieldName, value);
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
