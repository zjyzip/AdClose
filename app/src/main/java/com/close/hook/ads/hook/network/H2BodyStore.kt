package com.close.hook.ads.hook.network

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Chunks may only append to a stream registered by its request headers; eviction never restarts a body. */
internal class H2BodyStore(
    private val maxBodyBytes: Int = 5 * 1024 * 1024,
    private val maxRetainedBytes: Int = 32 * 1024 * 1024,
    private val maxStreams: Int = 1024
) {
    private data class Key(val connection: Long, val stream: Int)
    private class Body(var enabled: Boolean) { var bytes: ByteArrayOutputStream? = null }
    private class Stream(collectResponse: Boolean) {
        val request = Body(true)
        val response = Body(collectResponse)
    }
    private val streams = LinkedHashMap<Key, Stream>(16, 0.75f, true)
    private var retainedBytes = 0

    @Synchronized fun begin(connection: Long, stream: Int, collectResponse: Boolean) {
        val key = Key(connection, stream)
        if (streams.containsKey(key)) return
        if (streams.size >= maxStreams) {
            val first = streams.entries.iterator().next()
            release(first.value)
            streams.remove(first.key)
        }
        streams[key] = Stream(collectResponse)
    }

    @Synchronized fun append(connection: Long, stream: Int, request: Boolean, buffer: ByteBuffer) {
        val state = streams[Key(connection, stream)] ?: return
        val body = if (request) state.request else state.response
        if (!body.enabled || !buffer.hasRemaining()) return
        val count = buffer.remaining()
        val size = body.bytes?.size() ?: 0
        if (count > maxBodyBytes - size || count > maxRetainedBytes - retainedBytes) {
            drop(body)
            return
        }
        val output = body.bytes ?: ByteArrayOutputStream().also { body.bytes = it }
        val bytes = ByteArray(count)
        buffer.duplicate().get(bytes)
        output.write(bytes)
        retainedBytes += count
    }

    @Synchronized fun discard(connection: Long, stream: Int, request: Boolean) {
        val state = streams[Key(connection, stream)] ?: return
        drop(if (request) state.request else state.response)
    }

    @Synchronized fun takeBody(connection: Long, stream: Int, request: Boolean): ByteArray? {
        val state = streams[Key(connection, stream)] ?: return null
        val body = if (request) state.request else state.response
        val bytes = body.bytes?.toByteArray()
        drop(body)
        return bytes
    }

    @Synchronized fun take(connection: Long, stream: Int): Pair<ByteArray?, ByteArray?> {
        val state = streams.remove(Key(connection, stream)) ?: return null to null
        val result = state.request.bytes?.toByteArray() to state.response.bytes?.toByteArray()
        release(state)
        return result
    }

    @Synchronized fun close(connection: Long, stream: Int) {
        streams.remove(Key(connection, stream))?.let(::release)
    }

    @Synchronized fun closeConnection(connection: Long) {
        val iterator = streams.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.connection == connection) {
                release(entry.value)
                iterator.remove()
            }
        }
    }

    private fun drop(body: Body) {
        retainedBytes -= body.bytes?.size() ?: 0
        body.bytes = null
        body.enabled = false
    }
    private fun release(stream: Stream) { drop(stream.request); drop(stream.response) }
}
