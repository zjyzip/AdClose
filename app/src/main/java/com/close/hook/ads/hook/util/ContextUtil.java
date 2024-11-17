package com.close.hook.ads.hook.util;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.app.Instrumentation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedHelpers;

public class ContextUtil {

    public static volatile Context activityThreadContext;
    public static volatile Context appContext;
    public static volatile Context instrumentationContext;
    public static volatile Context contextWrapperContext;

    private static final List<Runnable> activityThreadContextCallbacks = new CopyOnWriteArrayList<>();
    private static final List<Runnable> appContextCallbacks = new CopyOnWriteArrayList<>();
    private static final List<Runnable> instrumentationContextCallbacks = new CopyOnWriteArrayList<>();
    private static final List<Runnable> contextWrapperContextCallbacks = new CopyOnWriteArrayList<>();

    private static final AtomicBoolean isActivityThreadContextInitialized = new AtomicBoolean(false);
    private static final AtomicBoolean isAppContextInitialized = new AtomicBoolean(false);
    private static final AtomicBoolean isInstrumentationContextInitialized = new AtomicBoolean(false);
    private static final AtomicBoolean isContextWrapperContextInitialized = new AtomicBoolean(false);

    /**
     * 初始化 ContextUtil，并注册回调
     */
    public static void initialize(Runnable initialCallback) {
        if (initialCallback != null) {
            // 每个回调独立注册，且不会互相影响
            addOnAppContextInitializedCallback(initialCallback);

            /*
            addOnActivityThreadContextInitializedCallback(initialCallback);
            addOnInstrumentationContextInitializedCallback(initialCallback);
            addOnContextWrapperContextInitializedCallback(initialCallback);
            */
        }
        // 启动上下文钩子的监控
        initializeContextHooks();
    }

    /**
     * 为各个 Context 的生命周期钩子绑定回调
     */
    private static void initializeContextHooks() {
        HookUtil.hookAllMethods(
            "android.app.ActivityThread",
            "performLaunchActivity",
            "after",
            param -> {
                Application mInitialApplication = (Application) XposedHelpers.getObjectField(param.thisObject, "mInitialApplication");

                Object activity = param.getResult();  // performLaunchActivity 返回的是 Activity 实例
                if (activity instanceof Context) {
                    activityThreadContext = (Context) activity;
                    triggerCallbacksIfInitialized(activityThreadContext, isActivityThreadContextInitialized, activityThreadContextCallbacks);
                }
            }
        );

        HookUtil.findAndHookMethod(
            Application.class,
            "attach",
            new Object[]{Context.class},
            "after",
            param -> {
                appContext = (Context) param.args[0];
                triggerCallbacksIfInitialized(appContext, isAppContextInitialized, appContextCallbacks);
            }
        );

        HookUtil.findAndHookMethod(
            Instrumentation.class,
            "callApplicationOnCreate",
            new Object[]{Application.class},
            "after",
            param -> {
                instrumentationContext = (Application) param.args[0];
                triggerCallbacksIfInitialized(instrumentationContext, isInstrumentationContextInitialized, instrumentationContextCallbacks);
            }
        );

        HookUtil.findAndHookMethod(
            ContextWrapper.class,
            "attachBaseContext",
            new Object[]{Context.class},
            "after",
            param -> {
                contextWrapperContext = (Context) param.args[0];
                triggerCallbacksIfInitialized(contextWrapperContext, isContextWrapperContextInitialized, contextWrapperContextCallbacks);
            }
        );
    }

    /**
     * 根据上下文初始化状态，触发对应的回调
     */
    private static void triggerCallbacksIfInitialized(Context context, AtomicBoolean isInitialized, List<Runnable> callbacks) {
        if (context != null && isInitialized.compareAndSet(false, true)) {
            synchronized (callbacks) {
                for (Runnable callback : callbacks) {
                    callback.run();
                }
                callbacks.clear(); // 清空回调，防止重复触发
            }
        }
    }

    /**
     * 添加 ActivityThread Context 初始化完成后的回调
     */
    public static void addOnActivityThreadContextInitializedCallback(Runnable callback) {
        addCallback(callback, isActivityThreadContextInitialized, activityThreadContextCallbacks);
    }

    /**
     * 添加 Application Context 初始化完成后的回调
     */
    public static void addOnAppContextInitializedCallback(Runnable callback) {
        addCallback(callback, isAppContextInitialized, appContextCallbacks);
    }

    /**
     * 添加 Instrumentation Context 初始化完成后的回调
     */
    public static void addOnInstrumentationContextInitializedCallback(Runnable callback) {
        addCallback(callback, isInstrumentationContextInitialized, instrumentationContextCallbacks);
    }

    /**
     * 添加 ContextWrapper Context 初始化完成后的回调
     */
    public static void addOnContextWrapperContextInitializedCallback(Runnable callback) {
        addCallback(callback, isContextWrapperContextInitialized, contextWrapperContextCallbacks);
    }

    /**
     * 添加回调到指定的回调列表中，且在初始化时立即触发
     */
    private static void addCallback(Runnable callback, AtomicBoolean isInitialized, List<Runnable> callbacks) {
        synchronized (callbacks) {
            if (isInitialized.get()) {
                // 如果已经初始化，则立即执行回调
                callback.run();
            } else {
                // 否则加入回调列表，并检查 race condition
                callbacks.add(callback);
                if (isInitialized.get()) {
                    // 这里再次检查是否已初始化，防止在添加过程中上下文已经初始化
                    callbacks.remove(callback);
                    callback.run();
                }
            }
        }
    }
}
