package com.close.hook.ads.hook.network

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID

/** Incremental HTTP/1 framing. Observation never consumes or alters the application's bytes. */
internal class Http1Connection(
    private val https: Boolean,
    private val collectBody: () -> Boolean,
    private val onRequest: (Request) -> Boolean,
    private val onResponse: (Response) -> Boolean,
    private val maxBodyBytes: Int = 5 * 1024 * 1024,
    private val maxRetainedBytes: Int = 32 * 1024 * 1024,
    private val onRequestBody: (Request) -> Unit = {},
    private val onResponseHeaders: (Response) -> Unit = {}
) {
    data class Request(
        val method: String, val target: String, val headers: String, val url: String?,
        val id: String = UUID.randomUUID().toString(), var body: ByteArray? = null
    )
    data class Response(val request: Request, val headers: String, val code: Int, val body: ByteArray?)

    private val pending = ArrayDeque<Request>()
    private var requests = Decoder(true)
    private var responses = Decoder(false)
    private var blocked = false
    private var unsupported = false
    private var responseObservationDisabled = false
    private var retainedBytes = 0
    val retainedBodyBytes: Int get() = retainedBytes
    val cacheWeightBytes: Int get() = 128 * 1024 + retainedBytes * 2 +
        pending.sumOf { it.headers.length * 4 + (it.url?.length ?: 0) * 2 } +
        (responses.request?.headers?.length ?: 0) * 4
    val pendingRequestIds: Set<String> get() = buildSet {
        pending.forEach { add(it.id) }
        requests.request?.let { add(it.id) }
        responses.request?.let { add(it.id) }
    }

    fun requestBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Boolean =
        feed(requests, bytes, offset, length)

    fun responseBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Boolean =
        if (responseObservationDisabled) blocked else feed(responses, bytes, offset, length)

    fun endOfInput(): Boolean {
        if (!unsupported && !blocked && responses.mode == Mode.UNTIL_CLOSE) responses.finish()
        val result = blocked
        clear()
        return result
    }

    private fun clear() {
        requests = Decoder(true)
        responses = Decoder(false)
        pending.clear()
        retainedBytes = 0
    }

    private fun failObservation() {
        unsupported = true
        clear()
    }

    private fun feed(decoder: Decoder, bytes: ByteArray, offset: Int, length: Int): Boolean {
        if (blocked || unsupported) return blocked
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        var cursor = offset
        val end = offset + length
        try {
            while (cursor < end && !blocked && !unsupported) {
                cursor = decoder.consume(bytes, cursor, end)
            }
        } catch (_: InvalidMessage) {
            failObservation()
        }
        return blocked
    }

    private enum class Mode { HEADERS, FIXED, CHUNK_SIZE, CHUNK_DATA, CHUNK_CRLF, TRAILERS, UNTIL_CLOSE }
    private class InvalidMessage : RuntimeException()
    private class LineBuffer : ByteArrayOutputStream() {
        fun fromEnd(offset: Int): Byte = buf[count - offset]
    }

    private inner class Capture(enabled: Boolean) {
        private var output: ByteArrayOutputStream? = if (enabled) ByteArrayOutputStream() else null
        fun append(bytes: ByteArray, start: Int, count: Int) {
            val out = output ?: return
            if (count > maxBodyBytes - out.size() || count > maxRetainedBytes - retainedBytes) {
                retainedBytes -= out.size()
                output = null
                return
            }
            out.write(bytes, start, count)
            retainedBytes += count
        }
        fun finish(): ByteArray? = output?.takeIf { it.size() > 0 }?.toByteArray().also { output = null }
    }

    private inner class Decoder(private val writing: Boolean) {
        var mode = Mode.HEADERS
        var request: Request? = null
        private var header = ""
        private var status = -1
        private var line = LineBuffer()
        private var headerBytes = 0
        private var remaining = 0L
        private var chunkCrlf = 0
        private var capture: Capture? = null
        private var dispatched = false
        private var upgrade = false

        fun consume(bytes: ByteArray, start: Int, end: Int): Int {
            if (mode == Mode.FIXED || mode == Mode.CHUNK_DATA || mode == Mode.UNTIL_CLOSE) {
                val count = if (mode == Mode.UNTIL_CLOSE) end - start else minOf(end - start.toLong(), remaining).toInt()
                capture?.append(bytes, start, count)
                if (mode != Mode.UNTIL_CLOSE) {
                    remaining -= count
                    if (remaining == 0L) {
                        if (mode == Mode.CHUNK_DATA) { mode = Mode.CHUNK_CRLF; chunkCrlf = 0 }
                        else finish()
                    }
                }
                return start + count
            }
            if (mode == Mode.CHUNK_CRLF) {
                val expected = if (chunkCrlf == 0) 13 else 10
                if ((bytes[start].toInt() and 255) != expected) throw InvalidMessage()
                if (++chunkCrlf == 2) mode = Mode.CHUNK_SIZE
                return start + 1
            }
            val byte = bytes[start].toInt() and 255
            if (byte == 0 || (byte < 32 && byte != 9 && byte != 10 && byte != 13)) throw InvalidMessage()
            line.write(byte)
            if (line.size() > MAX_HEADERS || ++headerBytes > MAX_HEADERS) throw InvalidMessage()
            val completedLine = byte == 10
            if (!completedLine) return start + 1
            if (line.size() < 2 || line.fromEnd(2) != 13.toByte()) throw InvalidMessage()
            if (mode == Mode.HEADERS && (line.size() < 4 || line.fromEnd(4) != 13.toByte() ||
                    line.fromEnd(3) != 10.toByte())) return start + 1
            val raw = line.toByteArray()
            when (mode) {
                Mode.HEADERS -> {
                    // Retain the complete header until CRLF CRLF, across arbitrarily split reads.
                    if (raw.size >= 4 && raw[raw.size - 4] == 13.toByte() && raw[raw.size - 3] == 10.toByte()) {
                        header = String(raw, 0, raw.size - 4, Charsets.ISO_8859_1)
                        line = LineBuffer()
                        startMessage()
                    }
                }
                Mode.CHUNK_SIZE -> {
                    val hex = String(raw, 0, raw.size - 2, Charsets.US_ASCII).substringBefore(';').trim()
                    if (hex.isEmpty() || hex.any { it.digitToIntOrNull(16) == null }) throw InvalidMessage()
                    val size = hex.toLongOrNull(16) ?: throw InvalidMessage()
                    line = LineBuffer()
                    headerBytes = 0
                    if (size == 0L) mode = Mode.TRAILERS else { remaining = size; mode = Mode.CHUNK_DATA }
                }
                Mode.TRAILERS -> {
                    line = LineBuffer()
                    if (raw.size == 2) finish()
                }
                else -> throw InvalidMessage()
            }
            return start + 1
        }

        private fun startMessage() {
            headerBytes = 0
            val firstLine = header.substringBefore("\r\n")
            val fields = parseHeaders(header)
            if (writing) {
                val parts = firstLine.split(' ', limit = 3)
                if (parts.size != 3 || !parts[2].matches(Regex("HTTP/1\\.[01]")) ||
                    parts[0].isEmpty() || parts[0].any { it <= ' ' || it >= '\u007f' }) throw InvalidMessage()
                val req = Request(parts[0], parts[1], header, requestUrl(parts[0], parts[1], fields["host"]?.singleOrNull(), https))
                request = req
                if (pending.size >= MAX_PENDING) {
                    // Keep request blocking active, but stop guessing response/request associations.
                    responseObservationDisabled = true
                    pending.clear()
                    responses = Decoder(false)
                    retainedBytes = 0
                }
                if (!responseObservationDisabled) pending.addLast(req)
                if (onRequest(req)) { blocked = true; clear(); return }
            } else {
                val parts = firstLine.split(' ', limit = 3)
                if (!parts.first().matches(Regex("HTTP/1\\.[01]"))) throw InvalidMessage()
                status = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 100..599 } ?: throw InvalidMessage()
                if (status in 100..199 && status != 101) { reset(); return }
                request = pending.removeFirstOrNull() ?: throw InvalidMessage()
            }
            val transfer = fields["transfer-encoding"].orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase(Locale.ROOT) }
            val lengths = fields["content-length"].orEmpty().flatMap { it.split(',') }.map {
                val value = it.trim()
                if (value.isEmpty() || value.any { char -> char !in '0'..'9' }) throw InvalidMessage()
                value.toLongOrNull() ?: throw InvalidMessage()
            }
            if (lengths.distinct().size > 1 || (transfer.isNotEmpty() && lengths.isNotEmpty())) throw InvalidMessage()
            val noBody = !writing && (request?.method == "HEAD" || status == 204 || status == 304 || status == 101 ||
                (request?.method == "CONNECT" && status in 200..299))
            upgrade = !writing && (status == 101 || (request?.method == "CONNECT" && status in 200..299))
            val collecting = if (writing) true else !responseObservationDisabled && collectBody()
            capture = Capture(collecting && !noBody)
            if (!writing) onResponseHeaders(Response(checkNotNull(request), header, status, null))
            if (!writing && !collecting) dispatch(null)
            when {
                noBody -> finish()
                transfer.isNotEmpty() && transfer.last() == "chunked" -> { mode = Mode.CHUNK_SIZE; headerBytes = 0 }
                transfer.isNotEmpty() && writing -> throw InvalidMessage()
                lengths.isNotEmpty() -> {
                    remaining = lengths.first()
                    if (remaining == 0L) finish() else mode = Mode.FIXED
                }
                writing -> finish()
                else -> mode = Mode.UNTIL_CLOSE
            }
        }

        fun finish() {
            val body = capture?.finish()
            if (writing) {
                val req = request
                if (req != null) {
                    req.body = body
                    onRequestBody(req)
                    if (pending.none { it.id == req.id } && responses.request?.id != req.id) {
                        retainedBytes = (retainedBytes - (body?.size ?: 0)).coerceAtLeast(0)
                        req.body = null
                    }
                }
            } else {
                if (!dispatched) dispatch(body)
                retainedBytes = (retainedBytes - (body?.size ?: 0) - (request?.body?.size ?: 0)).coerceAtLeast(0)
            }
            val upgraded = upgrade
            reset()
            if (upgraded) failObservation()
        }

        private fun dispatch(body: ByteArray?) {
            val req = request ?: return
            dispatched = true
            if (onResponse(Response(req, header, status, body))) {
                blocked = true
                clear()
            }
        }

        private fun reset() {
            mode = Mode.HEADERS
            request = null
            header = ""
            status = -1
            headerBytes = 0
            remaining = 0
            line = LineBuffer()
            capture = null
            dispatched = false
            upgrade = false
        }
    }

    companion object {
        private const val MAX_HEADERS = 64 * 1024
        private const val MAX_PENDING = 64

        fun parseHeaders(raw: String): Map<String, List<String>> {
            val result = linkedMapOf<String, MutableList<String>>()
            raw.split("\r\n").drop(1).forEach { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) throw InvalidMessage()
                val name = line.substring(0, separator).trim().lowercase(Locale.ROOT)
                result.getOrPut(name) { mutableListOf() }.add(line.substring(separator + 1).trim())
            }
            return result
        }

        private fun requestUrl(method: String, target: String, host: String?, https: Boolean): String? {
            if (target.startsWith("http://", true) || target.startsWith("https://", true)) return target
            val scheme = if (https) "https" else "http"
            if (method == "CONNECT") return "$scheme://$target"
            if (host.isNullOrBlank() || (target != "*" && !target.startsWith('/'))) return null
            return "$scheme://$host${if (target == "*") "/" else target}"
        }
    }
}
