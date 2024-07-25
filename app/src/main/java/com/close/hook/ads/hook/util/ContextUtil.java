package com.close.hook.ads.hook.util;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.app.Instrumentation;

import de.robv.android.xposed.XposedBridge;

public class ContextUtil {

    public static volatile Context instrumentationContext;
    public static volatile Context appContext;
    public static volatile Context contextWrapperContext;
    private static Runnable onAppContextInitialized;

    public static void initialize() {
        HookUtil.findAndHookMethod(
            Instrumentation.class,
            "callApplicationOnCreate",
            "after",
            param -> {
                Application application = (Application) param.args[0];
                instrumentationContext = application;
      //        XposedBridge.log("Instrumentation context initialized: " + instrumentationContext);
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
                ClassLoader classLoader = appContext.getClassLoader();
      //        XposedBridge.log("App context initialized with classloader: " + classLoader.toString());
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
      //        XposedBridge.log("ContextWrapper context initialized: " + contextWrapperContext);
                triggerContextInitialized();
            },
            Context.class
        );
    }

    private static synchronized void triggerContextInitialized() {
        if (appContext != null && instrumentationContext != null && contextWrapperContext != null) {
            if (onAppContextInitialized != null) {
                onAppContextInitialized.run();
                onAppContextInitialized = null;
            }
        }
    }

    public static void setOnAppContextInitialized(Runnable onAppContextInitialized) {
        ContextUtil.onAppContextInitialized = onAppContextInitialized;
        triggerContextInitialized();
    }
}
