package com.close.hook.ads.hook.network

/** Only suppress observation of module work; never suppress the application's original call. */
internal object NetworkObservationScope {
    private val depth = ThreadLocal.withInitial { 0 }
    val active: Boolean get() = (depth.get() ?: 0) > 0

    fun <T> run(action: () -> T): T {
        val previous = depth.get() ?: 0
        depth.set(previous + 1)
        val nativeScope = NativeRequestHook.beginJavaCapture()
        return try { action() }
        finally {
            if (nativeScope) NativeRequestHook.endJavaCapture()
            if (previous == 0) depth.remove() else depth.set(previous)
        }
    }
}
