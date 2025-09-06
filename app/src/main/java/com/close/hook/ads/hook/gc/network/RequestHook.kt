package com.close.hook.ads.hook.gc.network

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.contentValuesOf
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.RequestInfo
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.hook.util.ContextUtil
import com.close.hook.ads.hook.util.HookUtil
import com.close.hook.ads.hook.util.StringFinderKit
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.provider.TemporaryFileProvider
import com.close.hook.ads.provider.UrlContentProvider
import com.close.hook.ads.util.AppUtils
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Field
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLSocket

object RequestHook {

    private const val LOG_PREFIX = "[RequestHook] "
    private val UTF8: Charset = StandardCharsets.UTF_8

    private lateinit var applicationContext: Context

    private val dnsHostCache: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val urlStringCache: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val requestBuffers = ConcurrentHashMap<Int, ByteArrayOutputStream>()
    private val responseBuffers = ConcurrentHashMap<Int, ByteArrayOutputStream>()
    private val pendingRequests = ConcurrentHashMap<Int, BlockedRequest>()
    private val headerEndMarker = "\r\n\r\n".toByteArray()

    private val okHttp3OkioBufferCls: Class<*>? by lazy {
        try {
            XposedHelpers.findClass("okio.Buffer", applicationContext.classLoader)
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX ${e.message}")
            null
        }
    }

    private val okhttp3ResponseBodyClass: Class<*>? by lazy {
        try {
            XposedHelpers.findClass("okhttp3.ResponseBody", applicationContext.classLoader)
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX ${e.message}")
            null
        }
    }

    private val emptyWebResponse: WebResourceResponse? by lazy { createEmptyWebResourceResponse() }

    private val cronetHttpURLConnectionCls: Class<*>? by lazy {
        try {
            XposedHelpers.findClass("com.ttnet.org.chromium.net.urlconnection.CronetHttpURLConnection", applicationContext.classLoader)
        } catch (e: Throwable) { null }
    }
    private val urlResponseInfoCls: Class<*>? by lazy {
        try {
            XposedHelpers.findClass("com.ttnet.org.chromium.net.UrlResponseInfo", applicationContext.classLoader)
        } catch (e: Throwable) { null }
    }
    private val urlRequestCls: Class<*>? by lazy {
        try {
            XposedHelpers.findClass("com.ttnet.org.chromium.net.UrlRequest", applicationContext.classLoader)
        } catch (e: Throwable) { null }
    }
    private val cronetInputStreamCls: Class<*>? by lazy {
        try {
            XposedHelpers.findClass("com.ttnet.org.chromium.net.urlconnection.CronetInputStream", applicationContext.classLoader)
        } catch (e: Throwable) { null }
    }

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
                    setupDNSRequestHook()
                    setupSocketHook() // HTTP/1.1
                    setupConscryptEngineHook() // HTTPS over HTTP/1.1
                    setupOkHttpRequestHook() // HTTP/2 (via okhttp3)
                    // setupProtocolDowngradeHook()
                    setupWebViewRequestHook()
                    
                    if (context.packageName == "com.ss.android.ugc.aweme") {
                        setupCronetRequestHook()
                    }
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX Init error: ${e.message}")
        }
    }

    private fun formatUrlWithoutQuery(urlObject: Any?): String {
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

    private fun readStream(inputStream: InputStream): ByteArray {
        return try {
            inputStream.readBytes()
        } catch (e: IOException) {
            XposedBridge.log("$LOG_PREFIX Error reading stream: ${e.message}")
            ByteArray(0)
        }
    }

    private fun checkShouldBlockRequest(info: BlockedRequest?): Boolean {
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

    private fun setupDNSRequestHook() {
        HookUtil.findAndHookMethod(
            InetAddress::class.java,
            "getByName",
            arrayOf(String::class.java),
            "after"
        ) { param ->
            if (processDnsRequest(param.args[0], param.result, "getByName")) {
                param.result = null
            }
        }
        HookUtil.findAndHookMethod(
            InetAddress::class.java,
            "getAllByName",
            arrayOf(String::class.java),
            "after"
        ) { param ->
            if (processDnsRequest(param.args[0], param.result, "getAllByName")) {
                param.result = emptyArray<InetAddress>()
            }
        }
    }

    private fun processDnsRequest(hostObject: Any?, result: Any?, methodName: String): Boolean {
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

    fun setupSocketHook() {
        try {
            HookUtil.hookAllMethods(
                "java.net.SocketOutputStream",
                "socketWrite0",
                "before"
            ) { param ->
                val socket = XposedHelpers.getObjectField(param.thisObject, "socket")
                if (socket is SSLSocket) return@hookAllMethods

                val bytes = param.args[1] as ByteArray
                val offset = param.args[2] as Int
                val len = param.args[3] as Int
                if (len <= 0) return@hookAllMethods
                
                val key = System.identityHashCode(socket)
                val buffer = requestBuffers.getOrPut(key) { ByteArrayOutputStream() }
                buffer.write(bytes, offset, len)
                processRequestBuffer(key, false)  // isHttps = false
            }

            HookUtil.hookAllMethods(
                "java.net.SocketInputStream",
                "socketRead0",
                "after"
            ) { param ->
                val socket = XposedHelpers.getObjectField(param.thisObject, "socket")
                if (socket is SSLSocket) return@hookAllMethods

                val bytes = param.args[1] as ByteArray
                val len = param.result as? Int ?: -1
                if (len <= 0) return@hookAllMethods
                
                val key = System.identityHashCode(socket)
                val buffer = responseBuffers.getOrPut(key) { ByteArrayOutputStream() }
                buffer.write(bytes, 0, len)
                processResponseBuffer(key, param)
            }
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX Error setting up plain socket hook: ${e.message}")
        }
    }

    private fun setupProtocolDowngradeHook() {
        try {
            HookUtil.findAndHookMethod(
                SSLParameters::class.java,
                "setApplicationProtocols",
                arrayOf(Array<String>::class.java),
                "before"
            ) { param ->
                val originalProtocols = param.args[0] as? Array<String> ?: return@findAndHookMethod
                
                val filteredProtocols = originalProtocols.filter {
                    it.equals("http/1.1", ignoreCase = true)
                }

                val newProtocols = if (filteredProtocols.isEmpty() && originalProtocols.isNotEmpty()) {
                    arrayOf("http/1.1")
                } else {
                    filteredProtocols.toTypedArray()
                }

                if (!originalProtocols.contentEquals(newProtocols)) {
                    XposedBridge.log("$LOG_PREFIX Downgrading ALPN protocols from ${originalProtocols.joinToString()} to ${newProtocols.joinToString()}")
                    param.args[0] = newProtocols
                }
            }
        } catch(e: Throwable) {
            XposedBridge.log("$LOG_PREFIX Error setting up protocol downgrade hook: ${e.message}")
        }
    }

    private fun setupConscryptEngineHook() {
        try {
            val conscryptEngineClass = Class.forName("com.android.org.conscrypt.ConscryptEngine")

            HookUtil.findAndHookMethod(
                conscryptEngineClass,
                "wrap",
                arrayOf(ByteBuffer::class.java, ByteBuffer::class.java),
                "before"
            ) { param ->
                try {
                    val srcBuffer = param.args[0] as ByteBuffer
                    if (srcBuffer.hasRemaining()) {
                        val key = System.identityHashCode(param.thisObject)
                        val buffer = requestBuffers.getOrPut(key) { ByteArrayOutputStream() }
                        
                        val position = srcBuffer.position()
                        val bytes = ByteArray(srcBuffer.remaining())
                        srcBuffer.get(bytes)
                        srcBuffer.position(position)
                        
                        buffer.write(bytes)
                        processRequestBuffer(key, true)  // isHttps = true
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$LOG_PREFIX ConscryptEngine.wrap hook error: ${e.message}")
                }
            }

            HookUtil.findAndHookMethod(
                conscryptEngineClass,
                "unwrap",
                arrayOf(ByteBuffer::class.java, ByteBuffer::class.java),
                "after"
            ) { param ->
                try {
                    val dstBuffer = param.args[1] as ByteBuffer
                    val result = param.result as SSLEngineResult
                    val bytesProduced = result.bytesProduced()
                    
                    if (bytesProduced > 0) {
                        val key = System.identityHashCode(param.thisObject)
                        val buffer = responseBuffers.getOrPut(key) { ByteArrayOutputStream() }
                        
                        val position = dstBuffer.position()
                        val start = position - bytesProduced
                        val bytes = ByteArray(bytesProduced)
                        for (i in 0 until bytesProduced) {
                           bytes[i] = dstBuffer.get(start + i)
                        }

                        buffer.write(bytes)
                        processResponseBuffer(key, param)
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$LOG_PREFIX ConscryptEngine.unwrap hook error: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX Error setting up ConscryptEngine hook: ${e.message}")
        }
    }

    private fun processRequestBuffer(key: Int, isHttps: Boolean) {
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

    private fun processResponseBuffer(key: Int, param: XC_MethodHook.MethodHookParam) {
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
        val url = "$scheme://${host}${path}"

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

    fun setupOkHttpRequestHook() {
        // okhttp3.Call.execute
        hookOkHttpMethod("setupOkHttpRequestHook_execute", "Already Executed", "execute")
        // okhttp3.internal.http.RetryAndFollowUpInterceptor.intercept
        hookOkHttpMethod("setupOkHttp2RequestHook_intercept", "Canceled", "intercept")
    }

    private fun hookOkHttpMethod(cacheKeySuffix: String, methodDescription: String, methodName: String) {
        val cacheKey = "${applicationContext.packageName}:$cacheKeySuffix"
        StringFinderKit.findMethodsWithString(cacheKey, methodDescription, methodName)?.forEach { methodData ->
            try {
                val method = methodData.getMethodInstance(applicationContext.classLoader)
                XposedBridge.log("$LOG_PREFIX setupOkHttpRequestHook $methodData")
                HookUtil.hookMethod(method, "after") { param ->
                    try {
                        val response = param.result ?: return@hookMethod
                        val request = XposedHelpers.callMethod(response, "request")
                        val url = URL(XposedHelpers.callMethod(request, "url").toString())
                        val stackTrace = HookUtil.getFormattedStackTrace()

                        val requestBodyBytes = try {
                            XposedHelpers.callMethod(request, "body")?.let { requestBody ->
                                val bufferClass = XposedHelpers.findClass("okio.Buffer", applicationContext.classLoader)
                                val bufferInstance = bufferClass.getDeclaredConstructor().newInstance()
                                XposedHelpers.callMethod(requestBody, "writeTo", bufferInstance)
                                XposedHelpers.callMethod(bufferInstance, "readByteArray") as? ByteArray
                            }
                        } catch (e: Throwable) {
                            // XposedBridge.log("$LOG_PREFIX OkHttp request body reading failed (likely due to obfuscation): ${e.message}")
                            null
                        }

                        var responseBodyBytes: ByteArray? = null
                        var responseContentType: String? = null

                        if (HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)) {
                            val originalBody = XposedHelpers.callMethod(response, "body")
                            if (originalBody != null) {
                                try {
                                    val mediaType = XposedHelpers.callMethod(originalBody, "contentType")
                                    responseContentType = mediaType?.toString()
                                    responseBodyBytes = XposedHelpers.callMethod(originalBody, "bytes") as? ByteArray

                                    if (responseBodyBytes != null && okhttp3ResponseBodyClass != null) {
                                        val newBody = XposedHelpers.callStaticMethod(okhttp3ResponseBodyClass, "create", mediaType, responseBodyBytes)
                                        XposedHelpers.setObjectField(response, "body", newBody)
                                    }
                                } catch (e: Throwable) {
                                    XposedBridge.log("$LOG_PREFIX OkHttp response body reading failed (likely due to obfuscation): ${e.message}")
                                }
                            }
                        }

                        val info = buildOkHttpRequest(
                            url, " OKHTTP", request, response,
                            requestBodyBytes,
                            responseBodyBytes, responseContentType, stackTrace
                        )
                        if (checkShouldBlockRequest(info)) {
                            param.throwable = IOException("Request blocked by AdClose: ${url.host}")
                        }
                    } catch (e: IOException) {
                        param.throwable = e
                    } catch (e: Throwable) {
                        XposedBridge.log("$LOG_PREFIX OkHttp hook error ($methodName): ${e.message}")
                    }
                }
            } catch (e: Exception) {
                XposedBridge.log("$LOG_PREFIX Error hooking OkHttp method: $methodData, ${e.message}")
            }
        }
    }

    private fun buildOkHttpRequest(
        url: URL, requestFrameworkType: String, request: Any, response: Any,
        requestBody: ByteArray?,
        responseBody: ByteArray?, responseBodyContentType: String?, stack: String
    ): BlockedRequest? {
        return try {
            val method = XposedHelpers.callMethod(request, "method") as? String
            val urlString = url.toString()
            val requestHeaders = XposedHelpers.callMethod(request, "headers")?.toString()
            val code = XposedHelpers.callMethod(response, "code") as? Int ?: -1
            val message = XposedHelpers.callMethod(response, "message") as? String
            val responseHeaders = XposedHelpers.callMethod(response, "headers")?.toString()
            val formattedUrl = formatUrlWithoutQuery(url)
            BlockedRequest(
                requestType = requestFrameworkType,
                requestValue = formattedUrl,
                method = method,
                urlString = urlString,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = code,
                responseMessage = message,
                responseHeaders = responseHeaders,
                responseBody = responseBody,
                responseBodyContentType = responseBodyContentType,
                stack = stack,
                dnsHost = null,
                fullAddress = null
            )
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX buildOkHttpRequest error: ${e.message}")
            null
        }
    }

    fun setupWebViewRequestHook() {
        HookUtil.findAndHookMethod(
            WebView::class.java,
            "setWebViewClient",
            arrayOf(WebViewClient::class.java),
            "before"
        ) { param ->
            param.args[0]?.let {
                hookClientMethods(it.javaClass.name, applicationContext.classLoader)
            }
        }
    }

    private fun hookClientMethods(clientClassName: String, classLoader: ClassLoader) {
        XposedBridge.log("$LOG_PREFIX WebViewClient set: $clientClassName")

        HookUtil.findAndHookMethod(
            clientClassName,
            "shouldInterceptRequest",
            arrayOf(WebView::class.java, WebResourceRequest::class.java),
            "before",
            { param ->
                if (processWebRequest(param.args[1])) {
                    param.result = emptyWebResponse
                }
            },
            classLoader
        )

        HookUtil.findAndHookMethod(
            clientClassName,
            "shouldOverrideUrlLoading",
            arrayOf(WebView::class.java, WebResourceRequest::class.java),
            "before",
            { param ->
                if (processWebRequest(param.args[1])) {
                    param.result = true
                }
            },
            classLoader
        )
    }

    private fun processWebRequest(request: Any?): Boolean {
        try {
            val webResourceRequest = request as? WebResourceRequest ?: return false
            val urlString = webResourceRequest.url?.toString()
            val formattedUrl = urlString?.let { formatUrlWithoutQuery(Uri.parse(it)) } ?: return false
            val method = webResourceRequest.method
            val requestHeaders = webResourceRequest.requestHeaders?.toString()
            
            val info = BlockedRequest(
                requestType = " Web",
                requestValue = formattedUrl,
                method = method,
                urlString = urlString,
                requestHeaders = requestHeaders,
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
            return checkShouldBlockRequest(info)
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX Web request error: ${e.message}")
        }
        return false
    }

    private fun createEmptyWebResourceResponse(): WebResourceResponse? {
        return try {
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                204,
                "No Content",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0))
            )
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX Empty response error: ${e.message}")
            null
        }
    }

    fun setupCronetRequestHook() {
        try {
            HookUtil.hookAllMethods(
                cronetHttpURLConnectionCls,
                "getResponse",
                "after"
            ) { param ->
                try {
                    val thisObject = param.thisObject
                    var requestObject: Any? = null // mRequest
                    var responseInfoObject: Any? = null // mResponseInfo
                    var inputStreamObject: Any? = null // mInputStream

                    thisObject.javaClass.declaredFields.forEach { field ->
                        field.isAccessible = true
                        val value = field.get(thisObject)
                        when {
                            urlRequestCls?.isInstance(value) == true -> requestObject = value
                            urlResponseInfoCls?.isInstance(value) == true -> responseInfoObject = value
                            cronetInputStreamCls?.isInstance(value) == true -> inputStreamObject = value
                        }
                    }

                    if (requestObject == null || responseInfoObject == null) {
                        return@hookAllMethods
                    }

                    var initialUrl: String? = null // mInitialUrl
                    var method: String? = null // mInitialMethod
                    var requestHeaders: String? = null // mRequestHeaders
                    
                    requestObject.javaClass.declaredFields.forEach { field ->
                        field.isAccessible = true
                        when (val value = field.get(requestObject)) {
                            is String -> {
                                if (value.startsWith("http") && initialUrl == null) {
                                    initialUrl = value
                                } else if ((value.equals("GET", true) || value.equals("POST", true)) && method == null) {
                                    method = value
                                }
                            }
                            is List<*> -> {
                                if (requestHeaders == null) {
                                    requestHeaders = value.toString()
                                }
                            }
                        }
                    }

                    val finalUrl = XposedHelpers.callMethod(responseInfoObject, "getUrl") as? String
                    val httpStatusCode = XposedHelpers.callMethod(responseInfoObject, "getHttpStatusCode") as? Int ?: -1
                    val httpStatusText = XposedHelpers.callMethod(responseInfoObject, "getHttpStatusText") as? String
                    val responseHeadersMap = XposedHelpers.callMethod(responseInfoObject, "getAllHeaders") as? Map<*, *>
                    val responseHeaders = responseHeadersMap?.toString() ?: ""
                    val negotiatedProtocol = XposedHelpers.callMethod(responseInfoObject, "getNegotiatedProtocol") as? String
                    var responseBody: ByteArray? = null
                    var responseContentType: String? = null

                    if (HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false) && httpStatusCode in 200..399) {
                        inputStreamObject?.let { streamObj ->
                            var bufferField: Field? = null
                            try {
                                streamObj.javaClass.declaredFields.find { it.type == ByteBuffer::class.java }?.let {
                                    bufferField = it.apply { isAccessible = true }
                                }
                                bufferField?.get(streamObj)?.let { buffer ->
                                    (buffer as? ByteBuffer)?.takeIf { it.hasArray() }?.let {
                                        responseBody = it.array()
                                    }
                                }
                            } catch (e: Throwable) {
                                XposedBridge.log("$LOG_PREFIX Cronet buffer field access error: ${e.message}")
                            }

                            if (responseBody == null) {
                                (streamObj as? InputStream)?.use { inputStream ->
                                    responseBody = readStream(inputStream)
                                    if (bufferField != null && responseBody != null) {
                                        bufferField?.set(streamObj, ByteBuffer.wrap(responseBody))
                                    }
                                }
                            }

                            (responseHeadersMap as? Map<String, List<String>>)?.get("Content-Type")?.firstOrNull()?.let {
                                responseContentType = it
                            }
                        }
                    }

                    val stackTrace = HookUtil.getFormattedStackTrace()
                    val formattedUrl = finalUrl?.let { formatUrlWithoutQuery(Uri.parse(it)) }

                    val info = BlockedRequest(
                        requestType = " CRONET/$negotiatedProtocol",
                        requestValue = formattedUrl ?: "",
                        method = method,
                        urlString = finalUrl,
                        requestHeaders = requestHeaders,
                        requestBody = null,
                        responseCode = httpStatusCode,
                        responseMessage = httpStatusText,
                        responseHeaders = responseHeaders,
                        responseBody = responseBody,
                        responseBodyContentType = responseContentType,
                        stack = stackTrace,
                        dnsHost = null,
                        fullAddress = null
                    )

                    if (checkShouldBlockRequest(info)) {
                        param.throwable = IOException("Request blocked by AdClose")
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$LOG_PREFIX Error in Cronet hook: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX Error setting up Cronet hook: ${e.message}")
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
