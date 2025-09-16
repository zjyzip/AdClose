package com.close.hook.ads.hook.gc.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.core.content.contentValuesOf
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.RequestInfo
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.hook.util.ContextUtil
import com.close.hook.ads.hook.util.HookUtil
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.provider.TemporaryFileProvider
import com.close.hook.ads.provider.UrlContentProvider
import com.close.hook.ads.util.AppUtils
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object RequestHook {

    private const val LOG_PREFIX = "[RequestHook] "
    private val UTF8: Charset = StandardCharsets.UTF_8

    internal lateinit var applicationContext: Context

    private val dnsHostCache: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val urlStringCache: MutableSet<String> = ConcurrentHashMap.newKeySet()

    internal val requestBuffers = ConcurrentHashMap<Int, ByteArrayOutputStream>()
    internal val responseBuffers = ConcurrentHashMap<Int, ByteArrayOutputStream>()
    internal val pendingRequests = ConcurrentHashMap<Int, BlockedRequest>()
    private val headerEndMarker = "\r\n\r\n".toByteArray()
    
    private val URL_CONTENT_URI: Uri = Uri.Builder()
        .scheme("content")
        .authority(UrlContentProvider.AUTHORITY)
        .appendPath(UrlContentProvider.URL_TABLE_NAME)
        .build()

    private val queryCache: Cache<String, Triple<Boolean, String?, String?>> = CacheBuilder.newBuilder()
        .maximumSize(8192)
        .expireAfterAccess(4, TimeUnit.HOURS)
        .softValues()
        .build()

    fun init() {
        try {
            ContextUtil.addOnApplicationContextInitializedCallback {
                ContextUtil.applicationContext?.let { context ->
                    applicationContext = context
                    RequestHookHandler.init(context)
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX Init error: ${e.message}")
        }
    }

    internal fun formatUrlWithoutQuery(urlObject: Any?): String {
        return try {
            when (urlObject) {
                is URL -> {
                    val decodedPath = URLDecoder.decode(urlObject.path, UTF8.name())
                    URL(urlObject.protocol, urlObject.host, urlObject.port, decodedPath).toExternalForm()
                }
                is Uri -> {
                    val decodedPath = URLDecoder.decode(urlObject.path ?: "", UTF8.name())
                    Uri.Builder()
                        .scheme(urlObject.scheme)
                        .authority(urlObject.authority)
                        .path(decodedPath)
                        .build()
                        .toString()
                }
                else -> urlObject?.toString() ?: ""
            }
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX URL format error: ${e.message}")
            urlObject?.toString() ?: ""
        }
    }

    internal fun readStream(inputStream: InputStream): ByteArray {
        return try {
            inputStream.readBytes()
        } catch (e: IOException) {
            XposedBridge.log("$LOG_PREFIX Error reading stream: ${e.message}")
            ByteArray(0)
        }
    }

    internal fun checkShouldBlockRequest(info: BlockedRequest?): Boolean {
        info ?: return false
        val blockResult = sequenceOf("URL", "Domain", "KeyWord")
            .mapNotNull { type ->
                val value = if (type == "Domain") AppUtils.extractHostOrSelf(info.requestValue) else info.requestValue
                val result = queryContentProvider(type, value)
                if (result.first) result else null
            }
            .firstOrNull()
        blockResult?.let {
            sendBroadcast(info, true, it.second, it.third)
            return true
        }
        sendBroadcast(info, false, null, null)
        return false
    }

    private fun queryContentProvider(queryType: String, queryValue: String): Triple<Boolean, String?, String?> {
        val cacheKey = "$queryType:$queryValue"
        return queryCache.get(cacheKey) {
            try {
                applicationContext.contentResolver.query(
                    URL_CONTENT_URI,
                    arrayOf(Url.URL_TYPE, Url.URL_ADDRESS),
                    null,
                    arrayOf(queryType, queryValue),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val urlType = cursor.getString(cursor.getColumnIndexOrThrow(Url.URL_TYPE))
                        val urlAddress = cursor.getString(cursor.getColumnIndexOrThrow(Url.URL_ADDRESS))
                        return@get Triple(true, urlType, urlAddress)
                    }
                }
            } catch (e: Exception) {
                XposedBridge.log("$LOG_PREFIX Query error: ${e.message}")
            }
            Triple(false, null, null)
        }
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
        val bufferStream = requestBuffers[key] ?: return
        val buffer = bufferStream.toByteArray()
        var currentIndex = 0

        while (currentIndex < buffer.size) {
            val startIndex = currentIndex
            val headerEndIndex = findBytes(buffer, headerEndMarker, startIndex)
            if (headerEndIndex == -1) break

            val headerBytes = buffer.copyOfRange(startIndex, headerEndIndex)
            val headerString = headerBytes.toString(Charsets.UTF_8)

            if (headerString.startsWith("CONNECT ", ignoreCase = true)) {
                currentIndex = headerEndIndex + headerEndMarker.size
                continue
            }

            val contentLength = parseContentLength(headerString)
            val bodyStartIndex = headerEndIndex + headerEndMarker.size
            val totalRequestSize = bodyStartIndex + contentLength
            if (buffer.size < totalRequestSize) break

            val bodyBytes = if (contentLength > 0) buffer.copyOfRange(bodyStartIndex, totalRequestSize) else null

            buildHttpRequest(key, headerString, bodyBytes, isHttps)

            currentIndex = totalRequestSize
        }

        if (currentIndex > 0) {
            val remainingBytes = buffer.copyOfRange(currentIndex, buffer.size)
            if (remainingBytes.isNotEmpty()) {
                requestBuffers[key] = ByteArrayOutputStream().apply { write(remainingBytes) }
            } else {
                requestBuffers.remove(key)
            }
        }
    }

    internal fun processResponseBuffer(key: Int, param: XC_MethodHook.MethodHookParam) {
        val bufferStream = responseBuffers[key] ?: return
        val buffer = bufferStream.toByteArray()
        var currentIndex = 0

        while (currentIndex < buffer.size) {
            val requestInfo = pendingRequests[key]
            if (requestInfo == null) {
                responseBuffers.remove(key)
                return
            }

            val startIndex = currentIndex
            val headerEndIndex = findBytes(buffer, headerEndMarker, startIndex)
            if (headerEndIndex == -1) break

            val headerBytes = buffer.copyOfRange(startIndex, headerEndIndex)
            val headerString = headerBytes.toString(Charsets.ISO_8859_1)
            val contentLength = parseContentLength(headerString)
            val isChunked = headerString.contains("Transfer-Encoding: chunked", ignoreCase = true)

            val bodyStartIndex = headerEndIndex + headerEndMarker.size
            var totalResponseSize: Int
            var bodyBytes: ByteArray? = null

            if (isChunked) {
                val chunkedBodyResult = parseChunkedBody(buffer, bodyStartIndex)
                if (chunkedBodyResult == null) break
                
                totalResponseSize = chunkedBodyResult.second
                if (HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)) {
                    bodyBytes = chunkedBodyResult.first
                }
            } else {
                totalResponseSize = bodyStartIndex + contentLength
                if (buffer.size < totalResponseSize) break
                
                if (HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false) && contentLength > 0) {
                    bodyBytes = buffer.copyOfRange(bodyStartIndex, totalResponseSize)
                }
            }
            
            completeAndDispatchRequest(key, requestInfo, headerString, bodyBytes, param)
            
            currentIndex = totalResponseSize
        }

        if (currentIndex > 0) {
            val remainingBytes = buffer.copyOfRange(currentIndex, buffer.size)
            if (remainingBytes.isNotEmpty()) {
                responseBuffers[key] = ByteArrayOutputStream().apply { write(remainingBytes) }
            } else {
                responseBuffers.remove(key)
            }
        }
    }

    private fun buildHttpRequest(key: Int, headers: String, body: ByteArray?, isHttps: Boolean) {
        val lines = headers.lines()
        val requestLine = lines.firstOrNull()?.split(" ") ?: return
        if (requestLine.size < 2) return

        val method = requestLine[0]
        val path = requestLine[1]
        val host = lines.find { it.startsWith("Host:", ignoreCase = true) }?.substring(6)?.trim() ?: return
        val scheme = if (isHttps) "https" else "http"
        val url = "$scheme://$host$path"

        val info = BlockedRequest(
            requestType = if (isHttps) " HTTPS" else " HTTP",
            requestValue = formatUrlWithoutQuery(Uri.parse(url)),
            method = method,
            urlString = url,
            requestHeaders = headers,
            requestBody = body,
            responseCode = -1,
            responseMessage = null,
            responseHeaders = null,
            responseBody = null,
            responseBodyContentType = null,
            stack = HookUtil.getFormattedStackTrace(),
            dnsHost = null,
            fullAddress = null
        )
        pendingRequests[key] = info
    }

    private fun completeAndDispatchRequest(key: Int, requestInfo: BlockedRequest, headers: String, body: ByteArray?, param: XC_MethodHook.MethodHookParam) {
        val lines = headers.lines()
        val statusLine = lines.firstOrNull()?.split(" ", limit = 3) ?: return
        val responseCode = statusLine.getOrNull(1)?.toIntOrNull() ?: -1
        val responseMessage = statusLine.getOrNull(2) ?: ""
        val contentType = lines.find { it.startsWith("Content-Type:", ignoreCase = true) }?.substring(13)?.trim()
        val contentEncoding = lines.find { it.startsWith("Content-Encoding:", ignoreCase = true) }?.substring(17)?.trim()

        val mimeTypeForProvider = if (!contentEncoding.isNullOrEmpty()) {
            "$contentType; encoding=$contentEncoding"
        } else {
            contentType
        }

        val finalInfo = requestInfo.copy(
            responseCode = responseCode,
            responseMessage = responseMessage,
            responseHeaders = headers,
            responseBody = body,
            responseBodyContentType = mimeTypeForProvider
        )

        if (checkShouldBlockRequest(finalInfo)) {
            param.throwable = IOException("Request blocked by AdClose")
        }
        pendingRequests.remove(key)
    }

    private fun findBytes(data: ByteArray, pattern: ByteArray, startIndex: Int = 0): Int {
        for (i in startIndex..(data.size - pattern.size)) {
            if (data.sliceArray(i until i + pattern.size).contentEquals(pattern)) {
                return i
            }
        }
        return -1
    }

    private fun parseContentLength(headers: String): Int {
        return headers.lines().find { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substring(15)?.trim()?.toIntOrNull() ?: 0
    }

    private fun parseChunkedBody(buffer: ByteArray, bodyStartIndex: Int): Pair<ByteArray, Int>? {
        val bodyStream = ByteArrayOutputStream()
        var currentIndex = bodyStartIndex
        while (true) {
            val crlfIndex = findBytes(buffer, "\r\n".toByteArray(), currentIndex)
            if (crlfIndex == -1) return null

            val chunkSizeHex = String(buffer, currentIndex, crlfIndex - currentIndex, Charsets.US_ASCII)
            val chunkSize = chunkSizeHex.toIntOrNull(16) ?: 0
            
            if (chunkSize == 0) {
                currentIndex = crlfIndex + 2
                val finalCrlfIndex = findBytes(buffer, "\r\n".toByteArray(), currentIndex)
                if(finalCrlfIndex == -1) return null
                return bodyStream.toByteArray() to finalCrlfIndex + 2
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

    private fun sendBlockedRequestBroadcast(type: String, info: BlockedRequest, isBlocked: Boolean, ruleUrl: String?, blockRuleType: String?) {
        val added = info.dnsHost?.takeIf { it.isNotBlank() }?.let(dnsHostCache::add)
            ?: info.urlString?.takeIf { it.isNotBlank() }?.let(urlStringCache::add)
        if (added == false) return

        try {
            var requestBodyUriString: String? = null
            var responseBodyUriString: String? = null

            fun storeBody(body: ByteArray?, contentType: String?): String? {
                body ?: return null
                return try {
                    val values = contentValuesOf("body_content" to body, "mime_type" to contentType)
                    applicationContext.contentResolver.insert(TemporaryFileProvider.CONTENT_URI, values)?.toString()
                } catch (e: Exception) {
                    Log.e(LOG_PREFIX, "Error inserting body into provider: ${e.message}", e)
                    null
                }
            }

            requestBodyUriString = storeBody(info.requestBody, "text/plain")
            responseBodyUriString = storeBody(info.responseBody, info.responseBodyContentType)

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
            }.also {
                applicationContext.sendBroadcast(it)
            }
        } catch (e: Exception) {
            Log.w(LOG_PREFIX, "Broadcast send error.", e)
        }
    }
}
