package com.close.hook.ads.hook.util;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.app.Instrumentation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ContextUtil {

    public static volatile Context instrumentationContext;
    public static volatile Context appContext;
    public static volatile Context contextWrapperContext;
    private static final List<Runnable> contextInitializedCallbacks = new CopyOnWriteArrayList<>();
    private static volatile boolean isContextInitialized = false;

    public static void initialize(Runnable initialCallback) {
        if (initialCallback != null) {
            addOnAppContextInitializedCallback(initialCallback);
        }
        hookContextMethods();
    }

    private static void hookContextMethods() {
        HookUtil.findAndHookMethod(
            Instrumentation.class,
            "callApplicationOnCreate",
            "after",
            param -> {
                instrumentationContext = (Application) param.args[0];
                checkAndTriggerContextInitialized();
            },
            Application.class
        );

        HookUtil.findAndHookMethod(
            Application.class,
            "attach",
            "after",
            param -> {
                appContext = (Context) param.args[0];
                checkAndTriggerContextInitialized();
            },
            Context.class
        );

        HookUtil.findAndHookMethod(
            ContextWrapper.class,
            "attachBaseContext",
            "after",
            param -> {
                contextWrapperContext = (Context) param.args[0];
                checkAndTriggerContextInitialized();
            },
            Context.class
        );
    }

    private static void checkAndTriggerContextInitialized() {
        if (appContext != null && !isContextInitialized) {
            synchronized (ContextUtil.class) {
                if (!isContextInitialized) {
                    isContextInitialized = true;
                    for (Runnable callback : contextInitializedCallbacks) {
                        callback.run();
                    }
                    contextInitializedCallbacks.clear();
                }
            }
        }
    }

    public static void addOnAppContextInitializedCallback(Runnable callback) {
        if (isContextInitialized) {
            callback.run();
        } else {
            synchronized (ContextUtil.class) {
                if (isContextInitialized) {
                    callback.run();
                } else {
                    contextInitializedCallbacks.add(callback);
                }
            }
        }
    }
}
