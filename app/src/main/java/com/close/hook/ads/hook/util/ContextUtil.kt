package com.close.hook.ads.hook.util

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

object ContextUtil {

    private class ContextState {
        val isInitialized = AtomicBoolean(false)
        val callbacks = ConcurrentLinkedQueue<Runnable>()

        fun triggerCallbacks(context: Context, contextType: String) {
            if (isInitialized.compareAndSet(false, true)) {
                XposedBridge.log("$TAG | Context Initialized ($contextType): $context")
                callbacks.forEach { it.run() }
                callbacks.clear()
            }
        }

        fun addCallback(callback: Runnable, currentContext: Context?) {
            if (isInitialized.get() && currentContext != null) {
                callback.run()
            } else {
                callbacks.offer(callback)
            }
        }
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

    private const val TAG = "ContextUtil"

    fun setupContextHooks() {
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

    fun addOnActivityThreadContextInitializedCallback(callback: Runnable) {
        activityThreadState.addCallback(callback, activityThreadContext)
    }

    fun addOnApplicationContextInitializedCallback(callback: Runnable) {
        applicationState.addCallback(callback, applicationContext)
    }

    fun addOnContextWrapperContextInitializedCallback(callback: Runnable) {
        contextWrapperState.addCallback(callback, contextWrapperContext)
    }

    fun addOnInstrumentationContextInitializedCallback(callback: Runnable) {
        instrumentationState.addCallback(callback, instrumentationContext)
    }
}
