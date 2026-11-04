package com.close.hook.ads.hook.util

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

object ContextUtil {

    private const val TAG = "ContextUtil"

    private val hooksInstalled = AtomicBoolean(false)

    private class ContextState {
        private val callbacks = InitializationCallbacks { error ->
            XposedBridge.log("$TAG | Context callback failed: $error")
        }

        fun triggerCallbacks(context: Context, contextType: String) {
            if (callbacks.complete()) {
                XposedBridge.log("$TAG | Context Initialized ($contextType): $context")
            }
        }

        fun addCallback(callback: () -> Unit) = callbacks.add(callback)
    }

    private val activityThreadState = ContextState()
    private val applicationState = ContextState()
    private val contextWrapperState = ContextState()
    private val instrumentationState = ContextState()

    @JvmField
    @Volatile
    var activityThreadContext: Context? = null

    @JvmField
    @Volatile
    var applicationContext: Context? = null

    @JvmField
    @Volatile
    var contextWrapperContext: Context? = null

    @JvmField
    @Volatile
    var instrumentationContext: Context? = null

    fun setupContextHooks() {
        if (!hooksInstalled.compareAndSet(false, true)) return
        HookUtil.hookAllMethods(
            "android.app.ActivityThread",
            "performLaunchActivity",
            "after"
        ) { param ->
            (param.result as? Context)?.let { context ->
                activityThreadContext = context
                activityThreadState.triggerCallbacks(context, "ActivityThreadContext")
            }
        }

        HookUtil.findAndHookMethod(
            Application::class.java,
            "attach",
            arrayOf(Context::class.java),
            "after"
        ) { param ->
            (param.args[0] as? Context)?.let { context ->
                applicationContext = context
                applicationState.triggerCallbacks(context, "ApplicationContext")
            }
        }

        HookUtil.findAndHookMethod(
            ContextWrapper::class.java,
            "attachBaseContext",
            arrayOf(Context::class.java),
            "after"
        ) { param ->
            (param.args[0] as? Context)?.let { context ->
                contextWrapperContext = context
                contextWrapperState.triggerCallbacks(context, "ContextWrapperContext")
            }
        }

        HookUtil.findAndHookMethod(
            Instrumentation::class.java,
            "callApplicationOnCreate",
            arrayOf(Application::class.java),
            "after"
        ) { param ->
            (param.args[0] as? Application)?.let { context ->
                instrumentationContext = context
                instrumentationState.triggerCallbacks(context, "InstrumentationContext")
            }
        }
    }

    fun addOnActivityThreadContextInitializedCallback(callback: () -> Unit) {
        activityThreadState.addCallback(callback)
    }

    fun addOnApplicationContextInitializedCallback(callback: () -> Unit) {
        applicationState.addCallback(callback)
    }

    fun addOnContextWrapperContextInitializedCallback(callback: () -> Unit) {
        contextWrapperState.addCallback(callback)
    }

    fun addOnInstrumentationContextInitializedCallback(callback: () -> Unit) {
        instrumentationState.addCallback(callback)
    }
}
