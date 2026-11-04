package com.close.hook.ads.hook.network

import org.junit.Assert.*
import org.junit.Test

class Http1ConnectionTest {
    private class Fixture(var collect: Boolean = true, maxBody: Int = 1024, budget: Int = 4096) {
        val requests = mutableListOf<Http1Connection.Request>()
        val responses = mutableListOf<Http1Connection.Response>()
        val requestBodies = mutableListOf<ByteArray?>()
        val responseHeaders = mutableListOf<Http1Connection.Response>()
        val parser = Http1Connection(false, { collect }, { request ->
            requests += request
            request.target.contains("/ads")
        }, { responses += it; false }, maxBody, budget,
            onRequestBody = { requestBodies += it.body?.copyOf() },
            onResponseHeaders = { responseHeaders += it })
        fun request(text: String) = parser.requestBytes(text.toByteArray(Charsets.ISO_8859_1))
        fun response(text: String) = parser.responseBytes(text.toByteArray(Charsets.ISO_8859_1))
    }

    @Test fun requestBodyIsStillCapturedWhenResponseAssociationIsDisabled() {
        val f = Fixture(false, budget = 4096)
        repeat(65) { index ->
            f.request("POST /$index HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\n\r\ndata")
        }
        assertTrue(f.requests.size >= 65)
        assertTrue(f.requestBodies.any { it?.contentEquals("data".toByteArray()) == true })
    }

    @Test fun headerLimitResetsForEachMessageOnAReusedConnection() {
        val f = Fixture(false)
        val header = "GET / HTTP/1.1\r\nHost: x\r\nX-Filler: ${"a".repeat(48)}\r\n\r\n"
        repeat(1800) { assertFalse(f.request(header)) }
        assertEquals(1800, f.requests.size)
    }
    @Test fun uploadBodyIsPublishedWithoutAResponseAndWithResponseCaptureDisabled() {
        val f = Fixture(false)
        f.request("POST /upload HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\n\r\ndata")
        assertEquals("data", String(f.requestBodies.single()!!))
        assertTrue(f.responses.isEmpty())
    }

    @Test fun responseHeadersArePublishedBeforeAnUnfinishedStreamingBody() {
        val f = Fixture()
        f.request("GET /stream HTTP/1.1\r\nHost: x\r\n\r\n")
        f.response("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n")
        assertEquals(200, f.responseHeaders.single().code)
        assertTrue(f.responses.isEmpty())
    }

    @Test fun uploadBodyFinishingAfterAnEarlyResponseIsStillPublished() {
        val f = Fixture()
        f.request("POST /upload HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\n\r\n")
        f.response("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n")
        f.request("data")
        assertEquals("data", String(f.requestBodies.single()!!))
        assertEquals(403, f.responses.single().code)
        assertEquals(0, f.parser.retainedBodyBytes)
    }

    @Test fun hostlessHttp10RetainsItsTargetAndResponseAssociation() {
        val f = Fixture()
        f.request("POST /local HTTP/1.0\r\nContent-Length: 4\r\n\r\ndata")
        assertNull(f.requests.single().url)
        assertEquals("/local", f.requests.single().target)
        f.response("HTTP/1.0 200 OK\r\nContent-Length: 2\r\n\r\nok")
        assertEquals(f.requests.single().id, f.responses.single().request.id)
        assertEquals("ok", String(f.responses.single().body!!))
    }

    @Test fun requestsAreReportedAsSoonAsHeadersArriveEvenWithoutAResponse() {
        val f = Fixture()
        f.request("POST /pending HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\n\r\n")
        assertEquals("/pending", f.requests.single().target)
        assertTrue(f.responses.isEmpty())
        f.parser.endOfInput()
        assertEquals(1, f.requests.size)
        assertTrue(f.responses.isEmpty())
    }

    @Test fun blockedRequestsAreReportedBeforeTheyCanReceiveAResponse() {
        val f = Fixture()
        assertTrue(f.request("GET /ads HTTP/1.1\r\nHost: x\r\n\r\n"))
        assertEquals("/ads", f.requests.single().target)
        assertTrue(f.responses.isEmpty())
    }

    @Test fun splitHeadersAndNonZeroReadOffsets() {
        val f = Fixture()
        val req = "GET /path HTTP/1.1\r\nHost: example.com\r\n\r\n"
        req.toByteArray().forEach { f.parser.requestBytes(byteArrayOf(it)) }
        val response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
        val padded = "garbage$response ignored".toByteArray()
        f.parser.responseBytes(padded, 7, response.length)
        assertEquals("http://example.com/path", f.requests.single().url)
        assertEquals("ok", String(f.responses.single().body!!))
    }

    @Test fun disabledCaptureDoesNotDisableConnectionReuseOrPipelining() {
        val f = Fixture(false)
        f.request("POST /one HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\n\r\ndataGET /two HTTP/1.1\r\nHost: x\r\n\r\n")
        f.response("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndataHTTP/1.1 204 No Content\r\n\r\n")
        assertEquals(listOf("/one", "/two"), f.responses.map { it.request.target })
        assertTrue(f.responses.all { it.body == null })
        assertTrue(f.request("GET /ads HTTP/1.1\r\nHost: x\r\n\r\n"))
    }

    @Test fun chunkedRequestsAndResponsesConsumeEveryTrailerBeforeTheNextMessage() {
        val f = Fixture()
        f.request("POST /one HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "2;ext=v\r\nab\r\n1\r\nc\r\n0\r\nX-Trailer: one\r\nOther: two\r\n\r\n" +
            "GET /two HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals("abc", String(f.requests.first().body!!))
        val response = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "2\r\nok\r\n0\r\nX-Trailer: one\r\nOther: two\r\n\r\n" + "HTTP/1.1 204 Empty\r\n\r\n"
        response.toByteArray().forEach { f.parser.responseBytes(byteArrayOf(it)) }
        assertEquals(2, f.responses.size)
        assertEquals("ok", String(f.responses.first().body!!))
        assertEquals("/two", f.responses.last().request.target)
    }

    @Test fun head204And304ResponsesAreBodylessEvenWithALength() {
        val f = Fixture()
        for (method in listOf("HEAD", "GET", "GET")) f.request("$method / HTTP/1.1\r\nHost: x\r\n\r\n")
        f.response("HTTP/1.1 200 OK\r\nContent-Length: 5000\r\n\r\n" +
            "HTTP/1.1 204 No Content\r\n\r\nHTTP/1.1 304 Not Modified\r\nContent-Length: 5000\r\n\r\n")
        assertEquals(listOf(200, 204, 304), f.responses.map { it.code })
        assertTrue(f.responses.all { it.body == null })
    }

    @Test fun interimResponsesDoNotConsumeTheRequest() {
        val f = Fixture()
        f.request("GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        f.response("HTTP/1.1 103 Early Hints\r\nLink: /style\r\n\r\nHTTP/1.1 100 Continue\r\n\r\n" +
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
        assertEquals(1, f.responses.size)
        assertEquals(200, f.responses.single().code)
    }

    @Test fun closeDelimitedBodyFinishesAtEof() {
        val f = Fixture()
        f.request("GET / HTTP/1.0\r\nHost: x\r\n\r\n")
        f.response("HTTP/1.0 200 OK\r\n\r\nfirst")
        f.response("second")
        assertTrue(f.responses.isEmpty())
        f.parser.endOfInput()
        assertEquals("firstsecond", String(f.responses.single().body!!))
        assertEquals(0, f.parser.retainedBodyBytes)
    }

    @Test fun oversizedBodyIsDiscardedWithoutLosingTheNextRequest() {
        val f = Fixture(maxBody = 4, budget = 8)
        repeat(2) { f.request("GET /$it HTTP/1.1\r\nHost: x\r\n\r\n") }
        f.response("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\nab")
        f.response("cdefHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
        assertNull(f.responses.first().body)
        assertEquals("ok", String(f.responses.last().body!!))
        assertEquals(0, f.parser.retainedBodyBytes)
    }

    @Test fun pendingRequestBodiesShareABoundedBudget() {
        val f = Fixture(maxBody = 4, budget = 8)
        repeat(3) { f.request("POST /$it HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\n\r\ndata") }
        assertEquals(8, f.parser.retainedBodyBytes)
        assertNull(f.requests.last().body)
        repeat(3) { f.response("HTTP/1.1 204 Empty\r\n\r\n") }
        assertEquals(0, f.parser.retainedBodyBytes)
    }

    @Test fun blockingADecodedHeaderPersistsUntilConnectionClose() {
        val f = Fixture()
        assertTrue(f.request("GET /ads HTTP/1.1\r\nHost: x\r\n\r\n"))
        assertTrue(f.request("GET / HTTP/1.1\r\nHost: x\r\n\r\n"))
        assertEquals(1, f.requests.size)
    }

    @Test fun malformedFramingFailsObservationWithoutThrowingIntoApplicationCode() {
        for (header in listOf("Content-Length: -1", "Content-Length: +1", "Content-Length: 1\r\nContent-Length: 2",
            "Transfer-Encoding: chunked\r\nContent-Length: 5")) {
            val f = Fixture()
            assertFalse(f.request("POST / HTTP/1.1\r\nHost: x\r\n$header\r\n\r\n"))
            assertEquals(0, f.parser.retainedBodyBytes)
        }
    }

    @Test fun negativeChunksDoNotAllocateOrCorruptTheApplicationRead() {
        val f = Fixture()
        f.request("GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        assertFalse(f.response("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n-1\r\n"))
        assertTrue(f.responses.isEmpty())
    }

    @Test fun absoluteProxyTargetsAndConnectTunnelsAreHandled() {
        val f = Fixture()
        f.request("GET http://target/path HTTP/1.1\r\nHost: proxy\r\n\r\n")
        assertEquals("http://target/path", f.requests.single().url)
        f.response("HTTP/1.1 204 Empty\r\n\r\n")
        f.request("CONNECT target:443 HTTP/1.1\r\nHost: proxy\r\n\r\n")
        f.response("HTTP/1.1 200 Connection established\r\n\r\n")
        assertFalse(f.parser.requestBytes(byteArrayOf(0x16, 0x03, 0x01)))
    }

    @Test fun requestBlockingSurvivesResponseQueueOverflow() {
        val f = Fixture(false)
        repeat(70) { f.request("GET /$it HTTP/1.1\r\nHost: x\r\n\r\n") }
        assertTrue(f.request("GET /ads HTTP/1.1\r\nHost: x\r\n\r\n"))
    }

    @Test fun earlyResponseDoesNotLeakTheLaterRequestBodyBudget() {
        val f = Fixture(maxBody = 4, budget = 4)
        f.request("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\n\r\n")
        f.response("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n")
        f.request("data")
        assertEquals(0, f.parser.retainedBodyBytes)
    }
}
