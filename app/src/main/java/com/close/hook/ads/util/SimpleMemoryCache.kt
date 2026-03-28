package com.close.hook.ads.util

import com.close.hook.ads.preference.HookPrefs
import java.util.Collections
import java.util.LinkedHashMap

class SimpleMemoryCache {
    private data class CacheEntry(val data: Pair<ByteArray, String>, val timestamp: Long, val sizeBytes: Int)

    private val maximumSize = 1000
    private val maxSinglePayloadSize = 5 * 1024 * 1024

    private val expirationTimeMillis: Long
        get() = HookPrefs.getRequestCacheExpiration() * 60 * 1000L
    
    private val cache: MutableMap<String, CacheEntry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CacheEntry>(128, 0.75f, false) {
            override fun removeEldestEntry(eldest: Map.Entry<String, CacheEntry>): Boolean {
                return size > maximumSize
            }
        }
    )

    fun get(key: String): Pair<ByteArray, String>? {
        val entry = cache[key] ?: return null
        
        if (System.currentTimeMillis() - entry.timestamp > expirationTimeMillis) {
            cache.remove(key)
            return null
        }
        return entry.data
    }

    fun put(key: String, value: Pair<ByteArray, String>) {
        val payloadSize = value.first.size
        
        val safeValue = if (payloadSize > maxSinglePayloadSize) {
            Pair(value.first.copyOf(maxSinglePayloadSize), value.second)
        } else {
            value
        }

        cleanUpExpired()
        cache[key] = CacheEntry(safeValue, System.currentTimeMillis(), safeValue.first.size)
    }

    private fun cleanUpExpired() {
        val now = System.currentTimeMillis()
        val currentExpiration = expirationTimeMillis
        
        synchronized(cache) {
            val iterator = cache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.timestamp > currentExpiration) {
                    iterator.remove()
                } else {
                    break 
                }
            }
        }
    }
}
