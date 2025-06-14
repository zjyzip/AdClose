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

    fun initialize(initialCallback: Runnable?) {
        initialCallback?.let {
            addOnActivityThreadContextInitializedCallback(it)
            addOnApplicationContextInitializedCallback(it)
            addOnContextWrapperContextInitializedCallback(it)
            addOnInstrumentationContextInitializedCallback(it)
        }
        initializeContextHooks()
    }

    private fun initializeContextHooks() {
        HookUtil.hookAllMethods(
            "android.app.ActivityThread",
            "performLaunchActivity",
            "after"
        ) { param ->
            val activity = param.result
            if (activity is Context) {
                activityThreadContext = activity
                triggerCallbacksIfInitialized(
                    activityThreadContext,
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
            applicationContext = param.args[0] as Context
            triggerCallbacksIfInitialized(
                applicationContext,
                isApplicationContextInitialized,
                applicationContextCallbacks,
                "ApplicationContext"
            )
        }

        HookUtil.findAndHookMethod(
            ContextWrapper::class.java,
            "attachBaseContext",
            arrayOf(Context::class.java),
            "after"
        ) { param ->
            contextWrapperContext = param.args[0] as Context
            triggerCallbacksIfInitialized(
                contextWrapperContext,
                isContextWrapperContextInitialized,
                contextWrapperContextCallbacks,
                "ContextWrapperContext"
            )
        }

        HookUtil.findAndHookMethod(
            Instrumentation::class.java,
            "callApplicationOnCreate",
            arrayOf(Application::class.java),
            "after"
        ) { param ->
            instrumentationContext = param.args[0] as Application
            triggerCallbacksIfInitialized(
                instrumentationContext,
                isInstrumentationContextInitialized,
                instrumentationContextCallbacks,
                "InstrumentationContext"
            )
        }
    }

    private fun triggerCallbacksIfInitialized(
        context: Context?,
        isInitialized: AtomicBoolean,
        callbacks: ConcurrentLinkedQueue<Runnable>,
        contextType: String
    ) {
        if (context != null && isInitialized.compareAndSet(false, true)) {
            XposedBridge.log("$TAG | Initialized ($contextType): $context")
            while (true) {
                val callback = callbacks.poll() ?: break
                callback.run()
            }
        }
    }

    fun addOnActivityThreadContextInitializedCallback(callback: Runnable) {
        addCallback(callback, isActivityThreadContextInitialized, activityThreadContextCallbacks)
    }

    fun addOnApplicationContextInitializedCallback(callback: Runnable) {
        addCallback(callback, isApplicationContextInitialized, applicationContextCallbacks)
    }

    fun addOnContextWrapperContextInitializedCallback(callback: Runnable) {
        addCallback(callback, isContextWrapperContextInitialized, contextWrapperContextCallbacks)
    }

    fun addOnInstrumentationContextInitializedCallback(callback: Runnable) {
        addCallback(callback, isInstrumentationContextInitialized, instrumentationContextCallbacks)
    }

    private fun addCallback(
        callback: Runnable,
        isInitialized: AtomicBoolean,
        callbacks: ConcurrentLinkedQueue<Runnable>
    ) {
        if (isInitialized.get()) {
            callback.run()
        } else {
            callbacks.offer(callback)
        }
    }
}
