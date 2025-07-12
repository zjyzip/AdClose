package com.close.hook.ads.hook.util

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

object ContextUtil {

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

    private val activityThreadContextCallbacks = ConcurrentLinkedQueue<Runnable>()
    private val applicationContextCallbacks = ConcurrentLinkedQueue<Runnable>()
    private val contextWrapperContextCallbacks = ConcurrentLinkedQueue<Runnable>()
    private val instrumentationContextCallbacks = ConcurrentLinkedQueue<Runnable>()

    private val isActivityThreadContextInitialized = AtomicBoolean(false)
    private val isApplicationContextInitialized = AtomicBoolean(false)
    private val isContextWrapperContextInitialized = AtomicBoolean(false)
    private val isInstrumentationContextInitialized = AtomicBoolean(false)

    private const val TAG = "ContextUtil"

    fun setupContextHooks() {
        HookUtil.hookAllMethods(
            "android.app.ActivityThread",
            "performLaunchActivity",
            "after"
        ) { param ->
            (param.result as? Context)?.let { context ->
                activityThreadContext = context
                triggerCallbacksIfInitialized(
                    context,
                    isActivityThreadContextInitialized,
                    activityThreadContextCallbacks,
                    "ActivityThreadContext"
                )
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
                triggerCallbacksIfInitialized(
                    context,
                    isApplicationContextInitialized,
                    applicationContextCallbacks,
                    "ApplicationContext"
                )
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
                triggerCallbacksIfInitialized(
                    context,
                    isContextWrapperContextInitialized,
                    contextWrapperContextCallbacks,
                    "ContextWrapperContext"
                )
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
                triggerCallbacksIfInitialized(
                    context,
                    isInstrumentationContextInitialized,
                    instrumentationContextCallbacks,
                    "InstrumentationContext"
                )
            }
        }
    }

    private fun triggerCallbacksIfInitialized(
        context: Context?,
        isInitialized: AtomicBoolean,
        callbacks: ConcurrentLinkedQueue<Runnable>,
        contextType: String
    ) {
        if (context != null && isInitialized.compareAndSet(false, true)) {
            XposedBridge.log("$TAG | Context Initialized ($contextType): $context")
            callbacks.forEach { it.run() }
            callbacks.clear()
        }
    }

    fun addOnActivityThreadContextInitializedCallback(callback: Runnable) {
        addCallback(callback, isActivityThreadContextInitialized, activityThreadContextCallbacks, activityThreadContext)
    }

    fun addOnApplicationContextInitializedCallback(callback: Runnable) {
        addCallback(callback, isApplicationContextInitialized, applicationContextCallbacks, applicationContext)
    }

    fun addOnContextWrapperContextInitializedCallback(callback: Runnable) {
        addCallback(callback, isContextWrapperContextInitialized, contextWrapperContextCallbacks, contextWrapperContext)
    }

    fun addOnInstrumentationContextInitializedCallback(callback: Runnable) {
        addCallback(callback, isInstrumentationContextInitialized, instrumentationContextCallbacks, instrumentationContext)
    }
 
    private fun addCallback(
        callback: Runnable,
        isInitialized: AtomicBoolean,
        callbacks: ConcurrentLinkedQueue<Runnable>,
        currentContext: Context?
    ) {
        if (isInitialized.get() && currentContext != null) {
            callback.run()
        } else {
            callbacks.offer(callback)
        }
    }
}
