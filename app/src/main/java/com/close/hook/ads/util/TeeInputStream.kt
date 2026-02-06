package com.close.hook.ads.hook.util

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

class TeeInputStream(
    private val input: InputStream,
    private val tee: OutputStream,
    private val closeTee: Boolean = false,
    private val onClose: (() -> Unit)? = null
) : InputStream() {

    @Throws(IOException::class)
    override fun read(): Int {
        val ch = input.read()
        if (ch != -1) {
            try {
                tee.write(ch)
            } catch (e: IOException) {
            }
        }
        return ch
    }

    @Throws(IOException::class)
    override fun read(bts: ByteArray, off: Int, len: Int): Int {
        val n = input.read(bts, off, len)
        if (n > 0) {
            try {
                tee.write(bts, off, n)
            } catch (e: IOException) {
            }
        }
        return n
    }

    @Throws(IOException::class)
    override fun read(bts: ByteArray): Int {
        return read(bts, 0, bts.size)
    }

    @Throws(IOException::class)
    override fun available(): Int {
        return input.available()
    }

    @Throws(IOException::class)
    override fun close() {
        try {
            input.close()
        } finally {
            try {
                if (closeTee) {
                    tee.close()
                }
            } finally {
                onClose?.invoke()
            }
        }
    }
}
