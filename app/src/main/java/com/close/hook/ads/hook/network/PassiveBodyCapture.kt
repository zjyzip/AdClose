package com.close.hook.ads.hook.network

import java.io.ByteArrayOutputStream

/** Records only bytes already read by the application; never owns or closes its stream. */
internal class PassiveBodyCapture(
    private val maxBytes: Int,
    private val expectedLength: Long? = null,
    private val onComplete: (ByteArray?) -> Unit
) {
    private var output: ByteArrayOutputStream? = ByteArrayOutputStream()
    private var count = 0L
    @Volatile var isDone = false
        private set
    val weight: Int get() = synchronized(this) { 64 * 1024 + (output?.size() ?: 0) * 2 }

    @Synchronized
    fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (isDone || length <= 0) return
        count += length
        val out = output
        if (out != null) {
            if (length > maxBytes - out.size()) output = null
            else out.write(bytes, offset, length)
        }
        if (expectedLength != null && count == expectedLength) finish()
    }

    @Synchronized
    fun finish() {
        if (isDone) return
        isDone = true
        val bytes = output?.toByteArray()
        output = null
        onComplete(bytes)
    }

    @Synchronized
    fun cancel() {
        isDone = true
        output = null
    }
}
