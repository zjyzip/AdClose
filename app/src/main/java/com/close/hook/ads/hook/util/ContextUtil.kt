package com.close.hook.ads.hook.util

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

object ContextUtil {

    private const val TAG = "ContextUtil"

    private class ContextState {
        val isInitialized = AtomicBoolean(false)
        val callbacks = ConcurrentLinkedQueue<() -> Unit>()

        fun triggerCallbacks(context: Context, contextType: String) {
            if (isInitialized.compareAndSet(false, true)) {
                XposedBridge.log("$TAG | Context Initialized ($contextType): $context")
                callbacks.forEach { it() }
                callbacks.clear()
            }
        }

        fun addCallback(callback: () -> Unit, currentContext: Context?) {
            if (isInitialized.get() && currentContext != null) {
                callback()
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

    fun addOnActivityThreadContextInitializedCallback(callback: () -> Unit) {
        activityThreadState.addCallback(callback, activityThreadContext)
    }

    fun addOnApplicationContextInitializedCallback(callback: () -> Unit) {
        applicationState.addCallback(callback, applicationContext)
    }

    fun addOnContextWrapperContextInitializedCallback(callback: () -> Unit) {
        contextWrapperState.addCallback(callback, contextWrapperContext)
    }

    fun addOnInstrumentationContextInitializedCallback(callback: () -> Unit) {
        instrumentationState.addCallback(callback, instrumentationContext)
    }
}
