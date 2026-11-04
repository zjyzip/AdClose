package com.close.hook.ads.util

import org.junit.Assert.*
import org.junit.Test

class BodyUploadStoreTest {
    @Test fun reconstructsMultiMiBBodiesFromBinderSizedChunks() {
        val store = BodyUploadStore()
        val body = ByteArray(2 * 1024 * 1024 + 37) { (it % 251).toByte() }
        val token = store.begin(100, body.size, "application/json; encoding=gzip")
        var position = 0
        while (position < body.size) {
            val end = minOf(body.size, position + BodyUploadStore.CHUNK_BYTES)
            store.append(100, token, body.copyOfRange(position, end))
            position = end
        }
        val result = store.finish(100, token)
        assertArrayEquals(body, result.bytes)
        assertEquals("application/json; encoding=gzip", result.mime)
    }

    @Test fun uploadsCannotBeModifiedOrCommittedByAnotherUid() {
        val store = BodyUploadStore()
        val token = store.begin(100, 1, "text/plain")
        assertThrows(SecurityException::class.java) { store.append(200, token, byteArrayOf(1)) }
        assertThrows(SecurityException::class.java) { store.finish(200, token) }
        assertThrows(SecurityException::class.java) { store.abort(200, token) }
        store.append(100, token, byteArrayOf(1))
        assertArrayEquals(byteArrayOf(1), store.finish(100, token).bytes)
    }

    @Test fun expiryAndAbortReleaseTheReservedBudget() {
        var time = 0L
        val store = BodyUploadStore(4, 4) { time }
        val token = store.begin(1, 4, "text/plain")
        assertThrows(IllegalStateException::class.java) { store.begin(1, 1, "text/plain") }
        store.abort(1, token)
        store.begin(1, 4, "text/plain")
        time = 30_001
        store.begin(1, 4, "text/plain")
    }

    @Test fun anIncompleteCommitNeverReturnsAPartialBodyAndFreesItsReservation() {
        val store = BodyUploadStore(4, 4)
        val token = store.begin(1, 4, "text/plain")
        store.append(1, token, byteArrayOf(1))
        assertThrows(IllegalStateException::class.java) { store.finish(1, token) }
        val next = store.begin(1, 4, "text/plain")
        assertNotEquals(token, next)
    }

    @Test fun oversizedChunksAndBodiesAreRejected() {
        val store = BodyUploadStore()
        assertThrows(IllegalArgumentException::class.java) { store.begin(1, 6 * 1024 * 1024, "text/plain") }
        val token = store.begin(1, 1024 * 1024, "text/plain")
        assertThrows(IllegalArgumentException::class.java) {
            store.append(1, token, ByteArray(BodyUploadStore.CHUNK_BYTES + 1))
        }
    }
}
