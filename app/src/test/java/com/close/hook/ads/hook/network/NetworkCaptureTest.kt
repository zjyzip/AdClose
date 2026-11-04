package com.close.hook.ads.hook.network

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class NetworkCaptureTest {
    @Test fun requestBoundaryUrlUsesAuthorityOrHostWithoutLosingTheQuery() {
        assertEquals("https://example.com/upload?q=1", NetworkHeaders.http2Url(listOf(
            ":method" to "POST", ":scheme" to "https", ":authority" to "example.com", ":path" to "/upload?q=1")))
        assertEquals("https://example.com/path", NetworkHeaders.http2Url(listOf(
            ":method" to "GET", ":authority" to "", "host" to "example.com", ":path" to "/path")))
        assertNull(NetworkHeaders.http2Url(listOf(":method" to "GET", ":path" to "/relative")))
    }

    @Test fun engineSnapshotsUseOnlyTheSelectedPlaintextBuffersAndActualAdvancement() {
        val ignored = ByteBuffer.wrap("ignored".toByteArray())
        val first = ByteBuffer.wrap("xxabc".toByteArray()).apply { position(2) }
        val second = ByteBuffer.wrap("def-unused".toByteArray())
        val destination = ByteBuffer.allocateDirect(64)
        val args = arrayOf<Any>(arrayOf(ignored, first, second), 1, 2, destination)
        val snapshot = NetworkBuffers.snapshot(args, write = true)!!
        first.position(5)
        second.position(3)
        destination.position(9)
        assertEquals("abcdef", String(snapshot.copyAdvanced(6)!!))
        assertEquals(5, first.position())
        assertEquals(3, second.position())
        assertEquals(0, ignored.position())
        assertNull(snapshot.copyAdvanced(7))
    }

    @Test fun unwrapSnapshotsSupportDestinationArraysWithOffsetsAndNonZeroPositions() {
        val encrypted = ByteBuffer.wrap("ciphertext".toByteArray())
        val untouched = ByteBuffer.allocate(4)
        val destination = ByteBuffer.allocateDirect(32).apply { position(4) }
        val snapshot = NetworkBuffers.snapshot(arrayOf(encrypted, arrayOf(untouched, destination), 1, 1), false)!!
        destination.put("response".toByteArray())
        encrypted.position(5)
        assertEquals("response", String(snapshot.copyAdvanced(8)!!))
        assertEquals(12, destination.position())
        assertEquals(5, encrypted.position())
        assertEquals(0, untouched.position())
    }

    @Test fun urlNormalizationPreservesPlusesAndDecodesOnlyThePath() {
        assertEquals("https://example.com/a+b", NetworkUrl.withoutQuery("https://example.com/a+b?q=x#fragment"))
        assertEquals("https://example.com/a b/+", NetworkUrl.withoutQuery("https://example.com/a%20b/%2B?q=x"))
        assertEquals("https://example.com/path", NetworkUrl.withoutQuery("https://user:secret@example.com:443/path?q=x"))
        assertEquals("http://[::1]:8080/path", NetworkUrl.withoutQuery("http://[::1]:8080/path?x=1"))
    }

    @Test fun alternatingHttp2HeadersKeepEmptyValuesAndIgnoreAnIncompleteTail() {
        assertEquals(listOf("a" to "", "b" to "value"), NetworkHeaders.alternating("a\n\nb\nvalue\n"))
        assertEquals(listOf("a" to "value"), NetworkHeaders.alternating("a\nvalue\norphan\n"))
    }

    @Test fun byteBufferCaptureDoesNotChangeCallerPositionOrIncludeUnusedCapacity() {
        val source = ByteBuffer.allocate(32).put("prefixDATAignored".toByteArray())
        source.position(10)
        val bytes = NetworkBuffers.copyRange(source, 6, 4)
        assertEquals("DATA", String(bytes!!))
        assertEquals(10, source.position())
        assertNull(NetworkBuffers.copyRange(source, 31, 2))
        assertNull(NetworkBuffers.copyRange(source, -1, 1))
    }

    @Test fun passiveCaptureUsesTheActualReadOffsetAndCompletesOnlyOnce() {
        val results = mutableListOf<ByteArray?>()
        val capture = PassiveBodyCapture(10, 3) { results += it }
        capture.append("xxabyy".toByteArray(), 2, 2)
        assertFalse(capture.isDone)
        capture.append(byteArrayOf('c'.code.toByte()), 0, 1)
        capture.finish()
        assertEquals(1, results.size)
        assertEquals("abc", String(results.single()!!))
    }

    @Test fun captureOverflowDiscardsTheEntireBodyRatherThanTruncatingCompressedBytes() {
        val results = mutableListOf<ByteArray?>()
        val capture = PassiveBodyCapture(2) { results += it }
        capture.append("abc".toByteArray(), 0, 3)
        capture.finish()
        assertEquals(1, results.size)
        assertNull(results.single())
    }

    @Test fun closingAnIncompleteCaptureDoesNotPublishAPartialBody() {
        val results = mutableListOf<ByteArray?>()
        val capture = PassiveBodyCapture(10) { results += it }
        capture.append(byteArrayOf(1, 2), 0, 2)
        capture.cancel()
        capture.finish()
        assertTrue(results.isEmpty())
    }
}
