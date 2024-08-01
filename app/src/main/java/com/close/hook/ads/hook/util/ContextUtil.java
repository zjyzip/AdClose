package com.close.hook.ads.hook.util;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.app.Instrumentation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class ContextUtil {

    public static volatile Context instrumentationContext;
    public static volatile Context appContext;
    public static volatile Context contextWrapperContext;
    private static final List<Runnable> appContextInitializedCallbacks = new CopyOnWriteArrayList<>();
    private static final List<Runnable> instrumentationContextInitializedCallbacks = new CopyOnWriteArrayList<>();
    private static final List<Runnable> contextWrapperContextInitializedCallbacks = new CopyOnWriteArrayList<>();
    private static volatile boolean isAppContextInitialized = false;
    private static volatile boolean isInstrumentationContextInitialized = false;
    private static volatile boolean isContextWrapperContextInitialized = false;

    public static void initialize(Runnable initialCallback) {
        if (initialCallback != null) {
            addOnAppContextInitializedCallback(initialCallback);
            addOnInstrumentationContextInitializedCallback(initialCallback);
            addOnContextWrapperContextInitializedCallback(initialCallback);
        }
        initializeContextHooks();
    }

    private static void initializeContextHooks() {
        HookUtil.findAndHookMethod(
            Instrumentation.class,
            "callApplicationOnCreate",
            "after",
            param -> {
                instrumentationContext = (Application) param.args[0];
                triggerCallbacksIfInitialized(instrumentationContext, isInstrumentationContextInitialized, instrumentationContextInitializedCallbacks, updated -> isInstrumentationContextInitialized = updated);
            },
            Application.class
        );

        HookUtil.findAndHookMethod(
            Application.class,
            "attach",
            "after",
            param -> {
                appContext = (Context) param.args[0];
                triggerCallbacksIfInitialized(appContext, isAppContextInitialized, appContextInitializedCallbacks, updated -> isAppContextInitialized = updated);
            },
            Context.class
        );

        HookUtil.findAndHookMethod(
            ContextWrapper.class,
            "attachBaseContext",
            "after",
            param -> {
                contextWrapperContext = (Context) param.args[0];
                triggerCallbacksIfInitialized(contextWrapperContext, isContextWrapperContextInitialized, contextWrapperContextInitializedCallbacks, updated -> isContextWrapperContextInitialized = updated);
            },
            Context.class
        );
    }

    private static void triggerCallbacksIfInitialized(Context context, boolean isInitialized, List<Runnable> callbacks, Consumer<Boolean> setInitialized) {
        if (context != null && !isInitialized) {
            synchronized (ContextUtil.class) {
                if (!isInitialized) {
                    setInitialized.accept(true);
                    for (Runnable callback : callbacks) {
                        callback.run();
                    }
                    callbacks.clear();
                }
            }
        }
    }

    public static void addOnAppContextInitializedCallback(Runnable callback) {
        addCallback(callback, isAppContextInitialized, appContextInitializedCallbacks);
    }

    public static void addOnInstrumentationContextInitializedCallback(Runnable callback) {
        addCallback(callback, isInstrumentationContextInitialized, instrumentationContextInitializedCallbacks);
    }

    public static void addOnContextWrapperContextInitializedCallback(Runnable callback) {
        addCallback(callback, isContextWrapperContextInitialized, contextWrapperContextInitializedCallbacks);
    }

    private static void addCallback(Runnable callback, boolean isInitialized, List<Runnable> callbacks) {
        if (isInitialized) {
            callback.run();
        } else {
            synchronized (ContextUtil.class) {
                if (isInitialized) {
                    callback.run();
                } else {
                    callbacks.add(callback);
                }
            }
        }
    }
}
