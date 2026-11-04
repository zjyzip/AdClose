package com.close.hook.ads.hook.network

import java.nio.ByteBuffer

internal object NetworkBuffers {
    /** Track only plaintext buffers; nested engine overloads are filtered by the caller. */
    fun snapshot(args: Array<out Any?>, write: Boolean): Snapshot? {
        val indices = args.indices.filter { index ->
            args[index] is ByteBuffer || (args[index] as? Array<*>)?.all { it is ByteBuffer } == true
        }
        val index = (if (write) indices.firstOrNull() else indices.lastOrNull()) ?: return null
        val value = args[index]
        val buffers = if (value is ByteBuffer) listOf(value) else {
            val array = (value as Array<*>).filterIsInstance<ByteBuffer>()
            val offset = args.getOrNull(index + 1) as? Int ?: 0
            val length = args.getOrNull(index + 2) as? Int ?: array.size
            if (offset < 0 || length < 0 || offset > array.size - length) return null
            array.subList(offset, offset + length)
        }
        return Snapshot(buffers, buffers.map { it.position() })
    }

    class Snapshot internal constructor(private val buffers: List<ByteBuffer>, private val starts: List<Int>) {
        fun copyAdvanced(expected: Int): ByteArray? {
            if (expected <= 0 || expected > 5 * 1024 * 1024) return null
            val counts = buffers.mapIndexed { index, buffer -> buffer.position() - starts[index] }
            if (counts.any { it < 0 } || counts.sumOf { it.toLong() } != expected.toLong()) return null
            val bytes = ByteArray(expected)
            var offset = 0
            buffers.forEachIndexed { index, buffer ->
                buffer.duplicate().apply { position(starts[index]) }.get(bytes, offset, counts[index])
                offset += counts[index]
            }
            return bytes
        }
    }

    fun copyRange(buffer: ByteBuffer, start: Int, count: Int): ByteArray? {
        if (start < 0 || count < 0 || start > buffer.limit() - count) return null
        return ByteArray(count).also { bytes -> buffer.duplicate().apply { position(start) }.get(bytes) }
    }
}
