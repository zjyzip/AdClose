package com.close.hook.ads.util

import com.close.hook.ads.preference.HookPrefs
import java.util.concurrent.ConcurrentHashMap

class SimpleMemoryCache {
    private data class CacheEntry(val data: Pair<ByteArray, String>, val timestamp: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val expirationTimeMillis = HookPrefs.getRequestCacheExpiration() * 60 * 1000
    private val maximumSize = 8192

    fun get(key: String): Pair<ByteArray, String>? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > expirationTimeMillis) {
            cache.remove(key)
            return null
        }
        return entry.data
    }

    fun put(key: String, value: Pair<ByteArray, String>) {
        if (cache.size >= maximumSize) {
            cache.keys.firstOrNull()?.let { cache.remove(it) }
        }
        cache[key] = CacheEntry(value, System.currentTimeMillis())
    }
}