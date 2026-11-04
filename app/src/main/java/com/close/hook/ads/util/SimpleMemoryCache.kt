package com.close.hook.ads.util

import com.close.hook.ads.preference.HookPrefs

class SimpleMemoryCache(
    private val maxTotalBytes: Int = 64 * 1024 * 1024,
    private val maxSinglePayloadSize: Int = 5 * 1024 * 1024,
    private val expirationTimeMillis: () -> Long = { HookPrefs.getRequestCacheExpiration() * 60_000L },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private data class Entry(val data: Pair<ByteArray, String>, val created: Long)
    private val cache = linkedMapOf<String, Entry>()
    private var totalBytes = 0

    fun get(key: String): Pair<ByteArray, String>? {
        val expiration = expirationTimeMillis()
        return synchronized(cache) {
            val entry = cache[key] ?: return@synchronized null
            if (now() - entry.created >= expiration) {
                cache.remove(key)
                totalBytes -= entry.data.first.size
                null
            } else entry.data
        }
    }

    fun put(key: String, value: Pair<ByteArray, String>) {
        val size = value.first.size
        // Truncating compressed data creates an unreadable body, so reject oversized payloads instead.
        require(size <= maxSinglePayloadSize && size <= maxTotalBytes) { "Body exceeds cache limit" }
        val expiration = expirationTimeMillis()
        synchronized(cache) {
            val timestamp = now()
            val iterator = cache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (timestamp - entry.value.created >= expiration || entry.key == key) {
                    totalBytes -= entry.value.data.first.size
                    iterator.remove()
                }
            }
            while (cache.isNotEmpty() && (cache.size >= 1000 || size > maxTotalBytes - totalBytes)) {
                val oldest = cache.entries.iterator()
                totalBytes -= oldest.next().value.data.first.size
                oldest.remove()
            }
            cache[key] = Entry(value, timestamp)
            totalBytes += size
        }
    }
}
