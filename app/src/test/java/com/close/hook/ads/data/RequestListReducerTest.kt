package com.close.hook.ads.data

import com.close.hook.ads.data.model.RequestInfo
import org.junit.Assert.*
import org.junit.Test

class RequestListReducerTest {
    private fun request(id: String, host: String = "example.com", dns: Boolean = false,
        blocked: Boolean = false, time: Long = 1L, pkg: String = "test.app") = RequestInfo(
        appName = if (dns) "App DNS" else "App HTTPS", packageName = pkg, request = host,
        timestamp = time, requestType = if (blocked) "block" else "pass", isBlocked = blocked,
        url = null, blockType = null, method = if (dns) null else "GET",
        urlString = if (dns) null else "https://$host/", requestHeaders = null,
        requestBodyUriString = null, responseCode = -1, responseMessage = null,
        responseHeaders = null, responseBodyUriString = null, responseBodyContentType = null,
        stack = null, dnsHost = if (dns) host else null, fullAddress = if (dns) "1.2.3.4" else null,
        requestId = id, captureType = if (dns) "DNS" else "HTTPS"
    )

    @Test fun requestsWithoutResponsesAndBlockedRequestsRemainVisible() {
        var rows = RequestListReducer.append(emptyList(), request("pending"))
        rows = RequestListReducer.append(rows, request("blocked", blocked = true))
        assertEquals(listOf("blocked", "pending"), rows.map { it.requestId })
        assertTrue(rows.all { it.responseCode == -1 })
        assertTrue(rows.first().isBlocked == true)
    }

    @Test fun responseUpdatesTheOriginalRowAndRetainsBodiesAndStartTime() {
        val initial = request("one", time = 10).copy(requestBodyUriString = "content://request")
        var rows = RequestListReducer.append(emptyList(), initial)
        rows = RequestListReducer.append(rows, request("one", time = 20).copy(responseCode = 200,
            responseHeaders = "Content-Length: 2", responseBodyUriString = "content://response"))
        rows = RequestListReducer.append(rows, request("one", time = 30).copy(responseCode = 200))
        assertEquals(1, rows.size)
        assertEquals(10L, rows.single().timestamp)
        assertEquals(30L, rows.single().lastTimestamp)
        assertEquals("Content-Length: 2", rows.single().responseHeaders)
        assertEquals("content://request", rows.single().requestBodyUriString)
        assertEquals("content://response", rows.single().responseBodyUriString)
        assertEquals(rows, RequestListReducer.append(rows, initial))
    }

    @Test fun uploadCompletionAfterResponseDoesNotDiscardItsBodyOrDowngradeStatus() {
        var rows = RequestListReducer.append(emptyList(), request("one"))
        rows = RequestListReducer.append(rows, request("one", time = 2).copy(responseCode = 403,
            responseHeaders = "Content-Length: 0", responseBodyUriString = "content://response"))
        rows = RequestListReducer.append(rows, request("one", time = 3).copy(requestBodyUriString = "content://upload"))
        assertEquals(1, rows.size)
        assertEquals(403, rows.single().responseCode)
        assertEquals("content://upload", rows.single().requestBodyUriString)
        assertEquals("content://response", rows.single().responseBodyUriString)
        assertEquals("Content-Length: 0", rows.single().responseHeaders)
    }

    @Test fun detailHandoffSeesBodiesArrivingAfterTheRowWasTapped() {
        val tapped = request("detail-handoff")
        RequestDetailUpdates.update(tapped)
        RequestDetailUpdates.update(tapped.copy(responseCode = 200,
            responseHeaders = "Content-Length: 2", responseBodyUriString = "content://body"))
        val current = RequestDetailUpdates.current(tapped)
        assertEquals(200, current.responseCode)
        assertEquals("content://body", current.responseBodyUriString)
        assertEquals(tapped.requestId, current.requestId)
    }

    @Test fun detailHandoffPreservesOutOfOrderBodyUpdatesAndSeparatesPackages() {
        val first = request("detail-separated", pkg = "first.app")
        RequestDetailUpdates.update(first.copy(responseCode = 200, responseBodyUriString = "content://response"))
        RequestDetailUpdates.update(first.copy(requestBodyUriString = "content://upload"))
        RequestDetailUpdates.update(request("detail-separated", pkg = "second.app")
            .copy(responseBodyUriString = "content://other"))
        val current = RequestDetailUpdates.current(first)
        assertEquals(200, current.responseCode)
        assertEquals("content://upload", current.requestBodyUriString)
        assertEquals("content://response", current.responseBodyUriString)
    }

    @Test fun repeatedHttpRequestsKeepSeparateBodiesAndTransactions() {
        val rows = RequestListReducer.append(listOf(request("one")), request("two"))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.occurrenceCount == 1L })
    }

    @Test fun dnsObservationsMergeCaseAndTrailingDotWithoutMovingTheRow() {
        var rows = listOf(request("http"), request("dns-0", dns = true))
        repeat(19) { index ->
            rows = RequestListReducer.append(rows, request("dns-${index + 1}", "EXAMPLE.COM.",
                dns = true, time = index + 2L))
        }
        assertEquals(2, rows.size)
        assertEquals("http", rows.first().requestId)
        assertEquals("dns-0", rows.last().requestId)
        assertEquals("dns-19", rows.last().lastRequestId)
        assertEquals(20L, rows.last().occurrenceCount)
        assertEquals(20L, rows.last().lastTimestamp)
        assertEquals(1L, rows.last().timestamp)
        val duplicate = request("dns-19", "EXAMPLE.COM.", dns = true, time = 20)
        assertEquals(20L, RequestListReducer.append(rows, duplicate).last().occurrenceCount)
    }

    @Test fun dnsGroupsSeparateAppsBlockingAndResolutionFailure() {
        val samples = listOf(request("one", dns = true), request("two", dns = true, pkg = "other.app"),
            request("three", dns = true, blocked = true),
            request("four", dns = true).copy(fullAddress = "", responseMessage = "UnknownHostException"))
        val rows = samples.fold(emptyList<RequestInfo>()) { rows, item -> RequestListReducer.append(rows, item) }
        assertEquals(4, rows.size)
    }

    @Test fun countsSaturateAndListCapacityStaysBounded() {
        val initial = request("dns-0", dns = true).copy(occurrenceCount = Long.MAX_VALUE)
        val rows = RequestListReducer.append(listOf(initial), request("dns-1", dns = true))
        assertEquals(Long.MAX_VALUE, rows.single().occurrenceCount)
        val capped = RequestListReducer.append(listOf(request("one"), request("two")), request("three"), 2)
        assertEquals(listOf("three", "one"), capped.map { it.requestId })
    }
}
