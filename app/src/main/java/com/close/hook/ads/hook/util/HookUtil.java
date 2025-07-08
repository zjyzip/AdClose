package com.close.hook.ads.hook.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
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

    private static Class<?> resolveClass(Object clazzObj, ClassLoader classLoader) {
        if (clazzObj instanceof String) {
            return XposedHelpers.findClassIfExists((String) clazzObj, classLoader);
        } else if (clazzObj instanceof Class<?>) {
            return (Class<?>) clazzObj;
        }
        return null;
    }

    private static <T extends java.lang.reflect.Member> void performHook(T hookTarget, XC_MethodHook methodHook) {
        if (hookTarget == null) {
            XposedBridge.log("HookUtil - Attempted to hook a null target.");
            return;
        }
        try {
            if (hookTarget instanceof Method) {
                ((Method) hookTarget).setAccessible(true);
            } else if (hookTarget instanceof Constructor) {
                ((Constructor<?>) hookTarget).setAccessible(true);
            }
            XposedBridge.hookMethod(hookTarget, methodHook);
        } catch (Throwable e) {
            XposedBridge.log("HookUtil - Error occurred while hooking " + hookTarget.getName() + ": " + e.getMessage());
        }
    }

    public static void hookSingleMethod(
            ClassLoader classLoader,
            String className,
            String methodName,
            Object returnValue) {

        hookMultipleMethods(classLoader, className, new String[]{methodName}, returnValue);
    }

    public static void hookMultipleMethods(
            ClassLoader classLoader,
            String className,
            String[] methodNames,
            Object returnValue) {

        Class<?> actualClass = resolveClass(className, classLoader);
        if (actualClass == null) return;

        for (Method method : actualClass.getDeclaredMethods()) {
            for (String targetMethodName : methodNames) {
                if (method.getName().equals(targetMethodName)) {
                    performHook(method, new XC_MethodReplacement() {
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

        Class<?> actualClass = resolveClass(clazz, classLoader);
        if (actualClass == null) {
            XposedBridge.log("HookUtil: Class not found for " + clazz);
            return;
        }

        Class<?>[] classParams = (parameterTypes == null) ? new Class<?>[0] :
                Arrays.stream(parameterTypes)
                        .map(param -> resolveClass(param, classLoader))
                        .toArray(Class<?>[]::new);

        if (Arrays.asList(classParams).contains(null)) {
            XposedBridge.log("HookUtil - findAndHookMethod: One or more parameter types could not be resolved.");
            return;
        }

        try {
            Method method = actualClass.getDeclaredMethod(methodName, classParams);
            performHook(method, createMethodHook(hookType, action));
        } catch (NoSuchMethodException e) {
            XposedBridge.log("HookUtil - findAndHookMethod: Method not found " + methodName + " with specified parameters. Error: " + e.getMessage());
        } catch (Throwable e) {
            XposedBridge.log("HookUtil - findAndHookMethod: Error hooking method " + methodName + ". Error: " + e.getMessage());
        }
    }

    public static void hookMethod(Method method, String hookType, Consumer<XC_MethodHook.MethodHookParam> action) {
        performHook(method, createMethodHook(hookType, action));
    }

    public static void hookAllMethods(
            Object clazz,
            String methodName,
            String hookType,
            Consumer<XC_MethodHook.MethodHookParam> action) {
        hookAllMethods(clazz, methodName, hookType, action, null);
    }

    public static void hookAllMethods(
            Object clazz,
            String methodName,
            String hookType,
            Consumer<XC_MethodHook.MethodHookParam> action,
            ClassLoader classLoader) {

        Class<?> actualClass = resolveClass(clazz, classLoader);
        if (actualClass == null) {
            XposedBridge.log("HookUtil: Class not found for " + clazz);
            return;
        }

        try {
            XposedBridge.hookAllMethods(actualClass, methodName, createMethodHook(hookType, action));
        } catch (Throwable e) {
            XposedBridge.log("HookUtil - hookAllMethods: Error hooking all methods of " + methodName + ". Error: " + e.getMessage());
        }
    }

    public static void hookAllConstructors(
            Object clazz,
            String hookType,
            Consumer<XC_MethodHook.MethodHookParam> action) {
        hookAllConstructors(clazz, hookType, action, null);
    }

    public static void hookAllConstructors(
            Object clazz,
            String hookType,
            Consumer<XC_MethodHook.MethodHookParam> action,
            ClassLoader classLoader) {

        Class<?> actualClass = resolveClass(clazz, classLoader);
        if (actualClass == null) {
            XposedBridge.log("HookUtil: Class not found for " + clazz);
            return;
        }

        XC_MethodHook methodHook = createMethodHook(hookType, action);
        for (Constructor<?> constructor : actualClass.getDeclaredConstructors()) {
            performHook(constructor, methodHook);
        }
    }

    private static XC_MethodHook createMethodHook(
            String hookType,
            Consumer<XC_MethodHook.MethodHookParam> action) {

        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if ("before".equals(hookType)) {
                    action.accept(param);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if ("after".equals(hookType)) {
                    action.accept(param);
                }
            }
        };
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        if (clazz == null) {
            XposedBridge.log("HookUtil - setStaticObjectField: Class is null.");
            return;
        }
        try {
            XposedHelpers.setStaticObjectField(clazz, fieldName, value);
        } catch (Throwable e) {
            XposedBridge.log("HookUtil - setStaticObjectField: Error setting static field " + fieldName + " in class " + clazz.getName() + ". Error: " + e.getMessage());
        }
    }

    public static void setStaticObjectField(
            Object clazz,
            ClassLoader classLoader,
            String fieldName,
            Object value) {

        Class<?> actualClass = resolveClass(clazz, classLoader);
        if (actualClass != null) {
            setStaticObjectField(actualClass, fieldName, value);
        } else {
            XposedBridge.log("HookUtil - setStaticObjectField: Class not found for " + clazz);
        }
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        if (obj == null) {
            XposedBridge.log("HookUtil - setIntField: Object is null.");
            return;
        }
        try {
            XposedHelpers.setIntField(obj, fieldName, value);
        } catch (Throwable e) {
            XposedBridge.log("HookUtil - setIntField: Error setting int field " + fieldName + " for object " + obj.getClass().getName() + ". Error: " + e.getMessage());
        }
    }

    public static void setIntField(
            Object clazz,
            ClassLoader classLoader,
            Object obj,
            String fieldName,
            int value) {

        Class<?> actualClass = resolveClass(clazz, classLoader);
        if (actualClass != null && actualClass.isInstance(obj)) {
            setIntField(obj, fieldName, value);
        } else {
            XposedBridge.log("HookUtil - setIntField: Object is not an instance of " + clazz + " or class not found.");
        }
    }

    public static String getFormattedStackTrace() {
        StackTraceElement[] stackElements = Thread.currentThread().getStackTrace();
        StringBuilder stackTrace = new StringBuilder(stackElements.length * 100);

        stackTrace.append("Stack Trace:\n");

        for (int i = 3; i < stackElements.length; i++) {
            StackTraceElement element = stackElements[i];
            stackTrace.append("  ")
                      .append(element.getClassName())
                      .append(".")
                      .append(element.getMethodName())
                      .append("(line: ")
                      .append(element.getLineNumber())
                      .append(")\n");
        }
        return stackTrace.toString();
    }
}
