package com.close.hook.ads.hook.gc.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.contentValuesOf
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.RequestInfo
import com.close.hook.ads.hook.util.HookUtil
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.provider.TemporaryFileProvider
import com.close.hook.ads.data.repository.RuleRepository
import com.close.hook.ads.util.AppUtils
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object RequestHook {

    private const val LOG_PREFIX = "[RequestHook] "
    private val UTF8: Charset = StandardCharsets.UTF_8

    internal lateinit var applicationContext: Context

    internal val asyncBroadcastExecutor: ExecutorService = ThreadPoolExecutor(
        1, 1,
        0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(500),
        { runnable -> Thread(runnable, "AdClose-AsyncBroadcast").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    private val sentRequestsCache = CacheBuilder.newBuilder()
        .maximumSize(5000)
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .build<String, Boolean>()
        .asMap()

    internal val requestBuffers = CacheBuilder.newBuilder()
        .expireAfterAccess(3, TimeUnit.MINUTES)
        .build<Int, ByteArrayOutputStream>()
        .asMap()

    internal val responseBuffers = CacheBuilder.newBuilder()
        .expireAfterAccess(3, TimeUnit.MINUTES)
        .build<Int, ByteArrayOutputStream>()
        .asMap()

    internal val pendingRequests = CacheBuilder.newBuilder()
        .expireAfterAccess(3, TimeUnit.MINUTES)
        .build<Int, BlockedRequest>()
        .asMap()

    private val requestParsingStates = CacheBuilder.newBuilder()
        .expireAfterAccess(3, TimeUnit.MINUTES)
        .build<Int, ParsingState>()
        .asMap()

    private val responseParsingStates = CacheBuilder.newBuilder()
        .expireAfterAccess(3, TimeUnit.MINUTES)
        .build<Int, ParsingState>()
        .asMap()

    private data class ParsingState(
        var isHeaderParsed: Boolean = false,
        var contentLength: Int = -1,
        var isChunked: Boolean = false,
        var headerSize: Int = 0,
        var isIgnored: Boolean = false
    )

    private val headerEndMarker = "\r\n\r\n".toByteArray()

    fun init(context: Context) {
        applicationContext = context
        RuleRepository.init(context)
    }

    internal fun formatUrlWithoutQuery(urlObject: Any?): String {
        return try {
            when (urlObject) {
                is URL -> {
                    val decodedPath = URLDecoder.decode(urlObject.path, UTF8.name())
                    val portStr =
                        if (urlObject.port != -1 && urlObject.port != urlObject.defaultPort) ":${urlObject.port}" else ""
                    "${urlObject.protocol}://${urlObject.host}$portStr$decodedPath"
                }

                is Uri -> {
                    val decodedPath = URLDecoder.decode(urlObject.path ?: "", UTF8.name())
                    val port = urlObject.port
                    val host = urlObject.host ?: ""
                    val scheme = urlObject.scheme ?: "http"
                    val portStr = if (port != -1) ":$port" else ""
                    "$scheme://$host$portStr$decodedPath"
                }

                else -> urlObject?.toString() ?: ""
            }
        } catch (error: Exception) {
            XposedBridge.log("$LOG_PREFIX URL format error: ${error.message}")
            urlObject?.toString() ?: ""
        }
    }

    internal fun checkShouldBlockRequest(info: BlockedRequest?): Boolean {
        info ?: return false

        val requestValue = info.requestValue
        if (requestValue.isBlank()) {
            sendBroadcast(info, false, null, null)
            return false
        }

        val host = AppUtils.extractHostOrSelf(requestValue)
        val match = RuleRepository.shouldBlock(requestValue = requestValue, host = host)

        if (match.matched) {
            sendBroadcast(info, true, match.ruleType, match.ruleUrl)
            return true
        }

        sendBroadcast(info, false, null, null)
        return false
    }

    internal fun processDnsRequest(hostObject: Any?, result: Any?): Boolean {
        val host = hostObject as? String ?: return false
        val stackTrace = HookUtil.getFormattedStackTrace()

        val fullAddress = when (result) {
            is InetAddress -> result.hostAddress
            is Array<*> -> result.filterIsInstance<InetAddress>()
                .joinToString(", ") { it.hostAddress.orEmpty() }

            else -> null
        }

        if (fullAddress.isNullOrEmpty()) return false

        val info = BlockedRequest(
            requestType = " DNS",
            requestValue = host,
            method = null,
            urlString = null,
            requestHeaders = null,
            requestBody = null,
            responseCode = -1,
            responseMessage = null,
            responseHeaders = null,
            responseBody = null,
            responseBodyContentType = null,
            stack = stackTrace,
            dnsHost = host,
            fullAddress = fullAddress
        )

        return checkShouldBlockRequest(info)
    }

    internal fun processRequestBuffer(key: Int, isHttps: Boolean) {
        try {
            val state = requestParsingStates.computeIfAbsent(key) { ParsingState() }

            if (state.isIgnored) {
                requestBuffers[key]?.reset()
                return
            }

            val bufferStream = requestBuffers[key] ?: return
            val buffer = bufferStream.toByteArray()

            if (!state.isHeaderParsed) {
                val headerEndIndex = findBytes(buffer, headerEndMarker, 0)
                if (headerEndIndex != -1) {
                    state.isHeaderParsed = true
                    state.headerSize = headerEndIndex + headerEndMarker.size
                    val headerString = String(buffer, 0, headerEndIndex, Charsets.UTF_8)

                    if (headerString.startsWith("CONNECT ", ignoreCase = true)) {
                        cleanBuffer(bufferStream, buffer, state.headerSize)
                        state.isHeaderParsed = false
                        return
                    }

                    state.contentLength = parseContentLength(headerString)

                    val reqInfo = buildHttpRequestWithoutBody(headerString, isHttps)
                    if (reqInfo != null) {
                        pendingRequests[key] = reqInfo
                        val shouldBlock = checkShouldBlockRequest(reqInfo)

                        if (shouldBlock) {
                            state.isIgnored = true
                            requestBuffers.remove(key)
                        } else {
                            if (!HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false) && state.contentLength > 0) {
                                state.isIgnored = true
                                requestBuffers[key]?.reset()
                                return
                            }
                        }
                    }
                }
            }

            if (state.isHeaderParsed && !state.isIgnored) {
                if (buffer.size >= state.headerSize + state.contentLength) {
                    val bodyBytes = if (state.contentLength > 0) {
                        buffer.copyOfRange(state.headerSize, state.headerSize + state.contentLength)
                    } else {
                        null
                    }

                    pendingRequests[key]?.let { pending ->
                        pendingRequests[key] = pending.copy(requestBody = bodyBytes)
                    }

                    cleanBuffer(bufferStream, buffer, state.headerSize + state.contentLength)
                    state.isHeaderParsed = false
                }
            }
        } catch (_: Exception) {
            requestBuffers.remove(key)
            requestParsingStates.remove(key)
        }
    }

    internal fun processResponseBuffer(key: Int, param: XC_MethodHook.MethodHookParam?): Boolean {
        try {
            val state = responseParsingStates.computeIfAbsent(key) { ParsingState() }

            if (state.isIgnored) {
                responseBuffers[key]?.reset()
                return false
            }

            val requestInfo = pendingRequests[key] ?: return false
            val bufferStream = responseBuffers[key] ?: return false
            val buffer = bufferStream.toByteArray()

            if (!state.isHeaderParsed) {
                val headerEndIndex = findBytes(buffer, headerEndMarker, 0)
                if (headerEndIndex != -1) {
                    val headerString = String(buffer, 0, headerEndIndex, Charsets.ISO_8859_1)
                    state.isHeaderParsed = true
                    state.headerSize = headerEndIndex + headerEndMarker.size
                    state.contentLength = parseContentLength(headerString)
                    state.isChunked = headerString.contains("Transfer-Encoding: chunked", ignoreCase = true)

                    val collectResponseBody = HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)
                    if (!collectResponseBody) {
                        completeAndDispatchRequest(key, requestInfo, headerString, null, param)
                        state.isIgnored = true
                        responseBuffers[key]?.reset()
                        return false
                    }
                }
            }

            if (state.isHeaderParsed && !state.isIgnored) {
                if (!state.isChunked && bufferStream.size() < state.headerSize + state.contentLength) {
                    return false
                }

                val bodyStartIndex = state.headerSize
                var totalResponseSize = 0
                var bodyBytes: ByteArray? = null
                var complete = false

                if (state.isChunked) {
                    parseChunkedBody(buffer, bodyStartIndex)?.let { parsed ->
                        totalResponseSize = parsed.second
                        bodyBytes = parsed.first
                        complete = true
                    }
                } else {
                    totalResponseSize = bodyStartIndex + state.contentLength
                    if (buffer.size >= totalResponseSize) {
                        bodyBytes = if (state.contentLength > 0) {
                            buffer.copyOfRange(bodyStartIndex, totalResponseSize)
                        } else {
                            null
                        }
                        complete = true
                    }
                }

                if (complete) {
                    val headerString = String(
                        buffer,
                        0,
                        state.headerSize - headerEndMarker.size,
                        Charsets.ISO_8859_1
                    )

                    val isBlocked = completeAndDispatchRequest(
                        key = key,
                        requestInfo = requestInfo,
                        headers = headerString,
                        body = bodyBytes,
                        param = param
                    )

                    cleanBuffer(bufferStream, buffer, totalResponseSize)
                    responseParsingStates.remove(key)
                    requestParsingStates.remove(key)
                    pendingRequests.remove(key)

                    return isBlocked
                }
            }
        } catch (_: Exception) {
            responseBuffers.remove(key)
            responseParsingStates.remove(key)
            pendingRequests.remove(key)
        }

        return false
    }

    private fun cleanBuffer(stream: ByteArrayOutputStream, buffer: ByteArray, processedSize: Int) {
        stream.reset()
        if (buffer.size > processedSize) {
            stream.write(buffer, processedSize, buffer.size - processedSize)
        }
    }

    private fun buildHttpRequestWithoutBody(headers: String, isHttps: Boolean): BlockedRequest? {
        val lines = headers.lines()
        val requestLine = lines.firstOrNull()?.split(" ") ?: return null
        if (requestLine.size < 2) return null

        val method = requestLine[0]
        val path = requestLine[1]
        val host = lines.find { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?: return null

        val scheme = if (isHttps) "https" else "http"
        val url = "$scheme://$host$path"

        val firstNewline = headers.indexOf("\r\n")
        val cleanedHeaders = if (firstNewline != -1) headers.substring(firstNewline + 2) else headers

        return BlockedRequest(
            requestType = if (isHttps) " HTTPS" else " HTTP",
            requestValue = formatUrlWithoutQuery(Uri.parse(url)),
            method = method,
            urlString = url,
            requestHeaders = cleanedHeaders,
            requestBody = null,
            responseCode = -1,
            responseMessage = null,
            responseHeaders = null,
            responseBody = null,
            responseBodyContentType = null,
            stack = HookUtil.getFormattedStackTrace(),
            dnsHost = null,
            fullAddress = null
        )
    }

    private fun completeAndDispatchRequest(
        key: Int,
        requestInfo: BlockedRequest,
        headers: String,
        body: ByteArray?,
        param: XC_MethodHook.MethodHookParam?
    ): Boolean {
        val lines = headers.lines()
        val statusLine = lines.firstOrNull()?.split(" ", limit = 3) ?: return false
        val responseCode = statusLine.getOrNull(1)?.toIntOrNull() ?: -1
        val responseMessage = statusLine.getOrNull(2) ?: ""

        val contentType = lines.find { it.startsWith("Content-Type:", ignoreCase = true) }
            ?.substring(13)
            ?.trim()

        val contentEncoding = lines.find { it.startsWith("Content-Encoding:", ignoreCase = true) }
            ?.substring(17)
            ?.trim()

        val mimeTypeForProvider = if (!contentEncoding.isNullOrEmpty()) {
            "$contentType; encoding=$contentEncoding"
        } else {
            contentType
        }

        val firstNewline = headers.indexOf("\r\n")
        val cleanedHeaders = if (firstNewline != -1) headers.substring(firstNewline + 2) else headers

        val finalInfo = requestInfo.copy(
            responseCode = responseCode,
            responseMessage = responseMessage,
            responseHeaders = cleanedHeaders,
            responseBody = body,
            responseBodyContentType = mimeTypeForProvider
        )

        val shouldBlock = checkShouldBlockRequest(finalInfo)
        if (shouldBlock) {
            param?.throwable = IOException("Request blocked by AdClose")
        }

        pendingRequests.remove(key)
        return shouldBlock
    }

    private fun findBytes(data: ByteArray, pattern: ByteArray, startIndex: Int = 0): Int {
        if (pattern.isEmpty()) return startIndex
        if (startIndex >= data.size || pattern.size > data.size - startIndex) return -1

        val badCharShift = IntArray(256) { pattern.size }
        for (index in 0 until pattern.size - 1) {
            badCharShift[pattern[index].toInt() and 0xFF] = pattern.size - 1 - index
        }

        var offset = startIndex
        while (offset <= data.size - pattern.size) {
            var patternIndex = pattern.size - 1
            while (patternIndex >= 0 && pattern[patternIndex] == data[offset + patternIndex]) {
                patternIndex--
            }

            if (patternIndex < 0) return offset

            val badCharIndex = data[offset + pattern.size - 1].toInt() and 0xFF
            offset += badCharShift[badCharIndex]
        }

        return -1
    }

    private fun parseContentLength(headers: String): Int {
        return headers.lines()
            .find { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substring(15)
            ?.trim()
            ?.toIntOrNull()
            ?: 0
    }

    private fun parseChunkedBody(buffer: ByteArray, bodyStartIndex: Int): Pair<ByteArray, Int>? {
        val bodyStream = ByteArrayOutputStream()
        var currentIndex = bodyStartIndex
        val crlf = "\r\n".toByteArray()

        while (true) {
            val crlfIndex = findBytes(buffer, crlf, currentIndex)
            if (crlfIndex == -1) return null

            val chunkSizeStr = String(
                buffer,
                currentIndex,
                crlfIndex - currentIndex,
                Charsets.US_ASCII
            ).trim()

            val hexPart = chunkSizeStr.substringBefore(';').trim()
            val chunkSize = hexPart.toIntOrNull(16) ?: return null

            if (chunkSize == 0) {
                currentIndex = crlfIndex + 2
                val finalCrlfIndex = findBytes(buffer, crlf, currentIndex)
                if (finalCrlfIndex == -1) return null
                return bodyStream.toByteArray() to (finalCrlfIndex + 2)
            }

            val chunkDataStart = crlfIndex + 2
            val chunkDataEnd = chunkDataStart + chunkSize

            if (buffer.size < chunkDataEnd + 2) return null

            bodyStream.write(buffer, chunkDataStart, chunkSize)
            currentIndex = chunkDataEnd + 2
        }
    }

    private fun sendBroadcast(info: BlockedRequest, shouldBlock: Boolean, blockRuleType: String?, ruleUrl: String?) {
        sendBlockedRequestBroadcast("all", info, shouldBlock, ruleUrl, blockRuleType)
        sendBlockedRequestBroadcast(if (shouldBlock) "block" else "pass", info, shouldBlock, ruleUrl, blockRuleType)
    }

    private fun sendBlockedRequestBroadcast(
        type: String,
        info: BlockedRequest,
        isBlocked: Boolean,
        ruleUrl: String?,
        blockRuleType: String?
    ) {
        val key = info.dnsHost ?: info.urlString
        if (key.isNullOrEmpty()) return

        val hasResponse = info.responseCode != -1

        if (hasResponse) {
            val replaced = sentRequestsCache.replace(key, false, true)
            if (!replaced) {
                val existing = sentRequestsCache.putIfAbsent(key, true)
                if (existing != null) return
            }
        } else {
            val previous = sentRequestsCache.putIfAbsent(key, false)
            if (previous != null) return
        }

        asyncBroadcastExecutor.execute {
            try {
                fun storeBody(body: ByteArray?, contentType: String?): String? {
                    body ?: return null
                    return runCatching {
                        val values = contentValuesOf(
                            "body_content" to body,
                            "mime_type" to contentType
                        )
                        applicationContext.contentResolver
                            .insert(TemporaryFileProvider.CONTENT_URI, values)
                            ?.toString()
                    }.getOrElse { error ->
                        Log.e(LOG_PREFIX, "Error inserting body into provider: ${error.message}", error)
                        null
                    }
                }

                val requestBodyUriString = storeBody(info.requestBody, "text/plain")
                val responseBodyUriString = storeBody(info.responseBody, info.responseBodyContentType)

                val requestInfoForBroadcast = RequestInfo(
                    appName = "${applicationContext.applicationInfo.loadLabel(applicationContext.packageManager)}${info.requestType}",
                    packageName = applicationContext.packageName,
                    request = info.requestValue,
                    timestamp = System.currentTimeMillis(),
                    requestType = type,
                    isBlocked = isBlocked,
                    url = ruleUrl,
                    blockType = blockRuleType,
                    method = info.method,
                    urlString = info.urlString,
                    requestHeaders = info.requestHeaders,
                    requestBodyUriString = requestBodyUriString,
                    responseCode = info.responseCode,
                    responseMessage = info.responseMessage,
                    responseHeaders = info.responseHeaders,
                    responseBodyUriString = responseBodyUriString,
                    responseBodyContentType = info.responseBodyContentType,
                    stack = info.stack,
                    dnsHost = info.dnsHost,
                    fullAddress = info.fullAddress
                )

                Intent("com.rikkati.REQUEST").apply {
                    putExtra("request", requestInfoForBroadcast)
                    setPackage("com.close.hook.ads")
                }.also { intent ->
                    applicationContext.sendBroadcast(intent)
                }
            } catch (error: Exception) {
                Log.w(LOG_PREFIX, "Broadcast send error.", error)
            }
        }
    }
}
