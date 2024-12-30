package com.close.hook.ads.hook.util;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.app.Instrumentation;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XposedBridge;

public class ContextUtil {

    public static volatile Context activityThreadContext;
    public static volatile Context applicationContext;
    public static volatile Context contextWrapperContext;
    public static volatile Context instrumentationContext;

    private static final ConcurrentLinkedQueue<Runnable> activityThreadContextCallbacks = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Runnable> applicationContextCallbacks = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Runnable> contextWrapperContextCallbacks = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Runnable> instrumentationContextCallbacks = new ConcurrentLinkedQueue<>();

    private static final AtomicBoolean isActivityThreadContextInitialized = new AtomicBoolean(false);
    private static final AtomicBoolean isApplicationContextInitialized = new AtomicBoolean(false);
    private static final AtomicBoolean isContextWrapperContextInitialized = new AtomicBoolean(false);
    private static final AtomicBoolean isInstrumentationContextInitialized = new AtomicBoolean(false);

    private static final String TAG = "ContextUtil";

    public static void initialize(Runnable initialCallback) {
        if (initialCallback != null) {
            addOnActivityThreadContextInitializedCallback(initialCallback);
            addOnApplicationContextInitializedCallback(initialCallback);
            addOnContextWrapperContextInitializedCallback(initialCallback);
            addOnInstrumentationContextInitializedCallback(initialCallback);
        }
        initializeContextHooks();
    }

    private static void initializeContextHooks() {
        HookUtil.hookAllMethods(
            "android.app.ActivityThread",
            "performLaunchActivity",
            "after",
            param -> {
                Object activity = param.getResult();
                if (activity instanceof Context) {
                    activityThreadContext = (Context) activity;
                    triggerCallbacksIfInitialized(activityThreadContext, isActivityThreadContextInitialized, activityThreadContextCallbacks, "ActivityThreadContext");
                }
            }
        );

        HookUtil.findAndHookMethod(
            Application.class,
            "attach",
            new Object[]{Context.class},
            "after",
            param -> {
                applicationContext = (Context) param.args[0];
                triggerCallbacksIfInitialized(applicationContext, isApplicationContextInitialized, applicationContextCallbacks, "ApplicationContext");
            }
        );

        HookUtil.findAndHookMethod(
            ContextWrapper.class,
            "attachBaseContext",
            new Object[]{Context.class},
            "after",
            param -> {
                contextWrapperContext = (Context) param.args[0];
                triggerCallbacksIfInitialized(contextWrapperContext, isContextWrapperContextInitialized, contextWrapperContextCallbacks, "ContextWrapperContext");
            }
        );

        HookUtil.findAndHookMethod(
            Instrumentation.class,
            "callApplicationOnCreate",
            new Object[]{Application.class},
            "after",
            param -> {
                instrumentationContext = (Application) param.args[0];
                triggerCallbacksIfInitialized(instrumentationContext, isInstrumentationContextInitialized, instrumentationContextCallbacks, "InstrumentationContext");
            }
        );
    }

    private static void triggerCallbacksIfInitialized(Context context, AtomicBoolean isInitialized, ConcurrentLinkedQueue<Runnable> callbacks, String contextType) {
        if (context != null && isInitialized.compareAndSet(false, true)) {
            XposedBridge.log(TAG + " | Initialized (" + contextType + "): " + context);
            Runnable callback;
            while ((callback = callbacks.poll()) != null) {
                callback.run();
            }
        }
    }

    public static void addOnActivityThreadContextInitializedCallback(Runnable callback) {
        addCallback(callback, isActivityThreadContextInitialized, activityThreadContextCallbacks);
    }

    public static void addOnApplicationContextInitializedCallback(Runnable callback) {
        addCallback(callback, isApplicationContextInitialized, applicationContextCallbacks);
    }

    public static void addOnContextWrapperContextInitializedCallback(Runnable callback) {
        addCallback(callback, isContextWrapperContextInitialized, contextWrapperContextCallbacks);
    }

    public static void addOnInstrumentationContextInitializedCallback(Runnable callback) {
        addCallback(callback, isInstrumentationContextInitialized, instrumentationContextCallbacks);
    }

    private static void addCallback(Runnable callback, AtomicBoolean isInitialized, ConcurrentLinkedQueue<Runnable> callbacks) {
        if (isInitialized.get()) {
            callback.run();
        } else {
            callbacks.offer(callback);
        }
    }
}
