package com.close.hook.ads.hook.util

/** A one-shot initialization signal with no lost registrations during publication. */
internal class InitializationCallbacks(private val onFailure: (Throwable) -> Unit) {
    private val lock = Any()
    private var initialized = false
    private val pending = mutableListOf<() -> Unit>()

    fun add(callback: () -> Unit) {
        val runNow = synchronized(lock) {
            if (initialized) true else {
                pending += callback
                false
            }
        }
        if (runNow) invoke(callback)
    }

    fun complete(): Boolean {
        val callbacks = synchronized(lock) {
            if (initialized) return false
            initialized = true
            pending.toList().also { pending.clear() }
        }
        callbacks.forEach(::invoke)
        return true
    }

    private fun invoke(callback: () -> Unit) {
        try {
            callback()
        } catch (error: Throwable) {
            onFailure(error)
        }
    }
}
