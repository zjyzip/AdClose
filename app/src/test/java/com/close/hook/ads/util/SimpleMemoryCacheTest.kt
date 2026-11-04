package com.close.hook.ads.util

import org.junit.Assert.*
import org.junit.Test

class SimpleMemoryCacheTest {
    @Test fun evictsOldBodiesByTotalBytesInsteadOfEntryCountAlone() {
        val cache = SimpleMemoryCache(12, 8, { 60_000 }, { 0 })
        cache.put("one", ByteArray(6) to "text/plain")
        cache.put("two", ByteArray(6) to "text/plain")
        cache.put("three", ByteArray(6) to "text/plain")
        assertNull(cache.get("one"))
        assertNotNull(cache.get("two"))
        assertNotNull(cache.get("three"))
    }

    @Test fun replacementAndExpiryKeepTheByteCounterCorrect() {
        var time = 0L
        val cache = SimpleMemoryCache(12, 8, { 100 }, { time })
        cache.put("one", ByteArray(8) to "text/plain")
        cache.put("one", ByteArray(2) to "text/plain")
        cache.put("two", ByteArray(8) to "text/plain")
        assertNotNull(cache.get("one"))
        time = 101
        assertNull(cache.get("one"))
        cache.put("three", ByteArray(8) to "text/plain")
        assertNull(cache.get("two"))
        assertNotNull(cache.get("three"))
    }

    @Test fun oversizedCompressedPayloadsAreNotSilentlyTruncated() {
        val cache = SimpleMemoryCache(12, 4, { 100 }, { 0 })
        assertThrows(IllegalArgumentException::class.java) { cache.put("gzip", ByteArray(5) to "text/plain; encoding=gzip") }
        assertNull(cache.get("gzip"))
    }
}
