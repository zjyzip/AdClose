package com.close.hook.ads.hook.network

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class H2BodyStoreTest {
    private fun H2BodyStore.add(connection: Long, stream: Int, request: Boolean, text: String) =
        append(connection, stream, request, ByteBuffer.wrap(text.toByteArray()))

    @Test fun directionCompletionPublishesWithoutWaitingForTheOtherSide() {
        val store = H2BodyStore()
        store.begin(1, 1, true)
        store.add(1, 1, true, "upload")
        assertEquals("upload", String(store.takeBody(1, 1, true)!!))
        store.add(1, 1, false, "response")
        assertEquals("response", String(store.takeBody(1, 1, false)!!))
        assertNull(store.takeBody(1, 1, true))
        store.close(1, 1)
    }

    @Test fun responseCanFinishBeforeTheUploadWithoutLosingEitherBody() {
        val store = H2BodyStore()
        store.begin(1, 1, true)
        store.add(1, 1, false, "early response")
        assertEquals("early response", String(store.takeBody(1, 1, false)!!))
        store.add(1, 1, true, "late upload")
        assertEquals("late upload", String(store.takeBody(1, 1, true)!!))
    }

    @Test fun capturesBothDirectionsWithoutChangingCallerBufferAndRemovesOnCompletion() {
        val store = H2BodyStore()
        store.begin(1, 1, true)
        val buffer = ByteBuffer.wrap("xxrequest".toByteArray()).apply { position(2) }
        store.append(1, 1, true, buffer)
        store.add(1, 1, false, "response")
        val (request, response) = store.take(1, 1)
        assertEquals("request", String(request!!))
        assertEquals("response", String(response!!))
        assertEquals(2, buffer.position())
        assertEquals(null to null, store.take(1, 1))
    }

    @Test fun oversizedCompressedBodyNeverRestartsWithTheTail() {
        val store = H2BodyStore(maxBodyBytes = 4)
        store.begin(1, 1, true)
        store.add(1, 1, false, "1234")
        store.add(1, 1, false, "5")
        store.add(1, 1, false, "tail")
        assertNull(store.take(1, 1).second)
    }

    @Test fun evictionAndResetRejectLateChunksInsteadOfStartingNewBodies() {
        val store = H2BodyStore(maxStreams = 1)
        store.begin(1, 1, true)
        store.add(1, 1, false, "first")
        store.begin(1, 3, true)
        store.add(1, 1, false, "late-tail")
        assertEquals(null to null, store.take(1, 1))
        store.close(1, 3)
        store.add(1, 3, false, "late-tail")
        assertEquals(null to null, store.take(1, 3))
    }

    @Test fun streamAndConnectionClosureOnlyReleaseTheirOwnBodies() {
        val store = H2BodyStore()
        for ((connection, stream) in listOf(1L to 1, 1L to 3, 2L to 1)) {
            store.begin(connection, stream, true)
            store.add(connection, stream, false, "$connection/$stream")
        }
        store.close(1, 1)
        store.closeConnection(1)
        assertEquals(null to null, store.take(1, 1))
        assertEquals(null to null, store.take(1, 3))
        assertEquals("2/1", String(store.take(2, 1).second!!))
    }

    @Test fun globalBudgetIsReturnedAfterDiscardAndCompletedStreams() {
        val store = H2BodyStore(maxBodyBytes = 8, maxRetainedBytes = 8)
        store.begin(1, 1, true)
        store.begin(1, 3, true)
        store.add(1, 1, false, "12345678")
        store.add(1, 3, false, "overflow")
        assertNull(store.take(1, 3).second)
        store.discard(1, 1, false)
        store.begin(1, 5, true)
        store.add(1, 5, false, "12345678")
        assertEquals("12345678", String(store.take(1, 5).second!!))
    }

    @Test fun disablingCaptureDuringAResponseDropsTheWholeBodyEvenIfEnabledAgain() {
        val store = H2BodyStore()
        store.begin(1, 1, true)
        store.add(1, 1, false, "prefix")
        store.discard(1, 1, false)
        store.add(1, 1, false, "suffix")
        assertNull(store.take(1, 1).second)
        store.begin(1, 3, false)
        store.add(1, 3, false, "response")
        store.add(1, 3, true, "request")
        val (request, response) = store.take(1, 3)
        assertEquals("request", String(request!!))
        assertNull(response)
    }
}
