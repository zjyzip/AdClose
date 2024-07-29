package com.close.hook.ads.hook.util;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.app.Instrumentation;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class ContextUtil {

    public static volatile Context instrumentationContext;
    public static volatile Context appContext;
    public static volatile Context contextWrapperContext;
    private static final List<WeakReference<Runnable>> contextInitializedCallbacks = new ArrayList<>();
    private static boolean isContextInitialized = false;

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
                triggerContextInitialized();
            },
            Application.class
        );

        HookUtil.findAndHookMethod(
            Application.class,
            "attach",
            "after",
            param -> {
                appContext = (Context) param.args[0];
                triggerContextInitialized();
            },
            Context.class
        );

        HookUtil.findAndHookMethod(
            ContextWrapper.class,
            "attachBaseContext",
            "after",
            param -> {
                contextWrapperContext = (Context) param.args[0];
                triggerContextInitialized();
            },
            Context.class
        );
    }

    private static void triggerContextInitialized() {
        if (!isContextInitialized && appContext != null && instrumentationContext != null && contextWrapperContext != null) {
            isContextInitialized = true;
            List<WeakReference<Runnable>> callbacksToRun;
            synchronized (ContextUtil.class) {
                callbacksToRun = new ArrayList<>(contextInitializedCallbacks);
                contextInitializedCallbacks.clear();
            }
            for (WeakReference<Runnable> weakRef : callbacksToRun) {
                Runnable callback = weakRef.get();
                if (callback != null) {
                    callback.run();
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
                    contextInitializedCallbacks.add(new WeakReference<>(callback));
                }
            }
        }
    }

}
