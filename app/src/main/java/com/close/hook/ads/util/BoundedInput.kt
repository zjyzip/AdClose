package com.close.hook.ads.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Reads an external document without allowing an untrusted size to exhaust memory. */
fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
    require(maxBytes > 0) { "maxBytes must be positive" }

    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (output.size() + count > maxBytes) {
            throw IOException("Input exceeds ${maxBytes / (1024 * 1024)} MiB")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

fun InputStream.readTextLimited(maxBytes: Int): String =
    readBytesLimited(maxBytes).toString(Charsets.UTF_8)
