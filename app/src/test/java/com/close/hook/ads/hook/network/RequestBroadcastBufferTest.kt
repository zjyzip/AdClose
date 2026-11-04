package com.close.hook.ads.hook.network

import com.close.hook.ads.data.model.BlockedRequest
import org.junit.Assert.*
import org.junit.Test

class RequestBroadcastBufferTest {
    private fun item(id: String, target: String = "ads.example.com", type: String = " HTTPS",
        body: ByteArray? = null, response: Int = -1) = BlockedRequest(
        requestType = type, requestValue = target, method = "GET", urlString = "https://$target/path",
        requestHeaders = "Host: $target", requestBody = body, responseCode = response,
        responseMessage = null, responseHeaders = null, responseBody = null,
        responseBodyContentType = null, stack = null, dnsHost = if (type.trim() == "DNS") target else null,
        fullAddress = null, requestId = id
    )

    @Test fun hundredsOfBlockedRetriesBecomeOnePendingEventWithAStableCount() {
        val buffer = RequestBroadcastBuffer(now = { 1L })
        repeat(100) { index ->
            buffer.offer(item("request-$index"), blocked = true, rule = "ads.example.com",
                ruleType = "Domain", timestamp = index.toLong())
        }
        val events = buffer.drain()
        assertEquals(1, events.size)
        assertEquals(100L, events.single().count)
        assertTrue(events.single().blocked)
        assertEquals("ads.example.com", events.single().info.requestValue)
    }

    @Test fun repeatedDnsAndConnectObservationsUseDifferentStableGroups() {
        val buffer = RequestBroadcastBuffer(now = { 1L })
        repeat(3) { index ->
            buffer.offer(item("dns-$index", "Example.COM.", " DNS"), false, null, null, index.toLong())
            buffer.offer(item("connect-$index", "1.2.3.4", " CONNECT"), false, null, null, index.toLong())
        }
        val events = buffer.drain()
        assertEquals(2, events.size)
        assertEquals(setOf(3L), events.map { it.count }.toSet())
        assertEquals(setOf("DNS", "CONNECT"), events.map { it.info.requestType.trim() }.toSet())
    }

    @Test fun requestBodyAndResponseUpdatesRemainOneEventAndKeepBothBodies() {
        val buffer = RequestBroadcastBuffer(now = { 1L })
        buffer.offer(item("one"), false, null, null, 1L)
        buffer.offer(item("one", body = "upload".toByteArray()), false, null, null, 2L)
        buffer.offer(item("one", response = 200).copy(responseBody = "response".toByteArray()), false, null, null, 3L)
        val events = buffer.drain()
        assertEquals(1, events.size)
        assertEquals("upload", String(events.single().info.requestBody!!))
        assertEquals("response", String(events.single().info.responseBody!!))
        assertEquals(200, events.single().info.responseCode)
    }

    @Test fun fullQueueDropsNewPassEventsButKeepsBlockedAndBodyUpdates() {
        val buffer = RequestBroadcastBuffer(maxPending = 2, now = { 1L })
        buffer.offer(item("one"), false, null, null, 1L)
        buffer.offer(item("two"), false, null, null, 2L)
        buffer.offer(item("three"), false, null, null, 3L)
        buffer.offer(item("blocked"), true, "blocked", "Domain", 4L)
        val events = buffer.drain()
        assertEquals(2, events.size)
        assertTrue(events.any { it.blocked })
    }

    @Test fun failedDrainCanBeRetriedWithoutDuplicatingTheSameEvent() {
        val buffer = RequestBroadcastBuffer(now = { 1L })
        buffer.offer(item("one"), true, "one", "URL", 1L)
        val events = buffer.drain()
        assertEquals(1, events.size)
        buffer.retry(events)
        buffer.retry(events)
        assertEquals(1, buffer.drain().size)
    }
}
