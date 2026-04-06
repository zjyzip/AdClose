package com.close.hook.ads.hook.gc.network

import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.hook.util.HookUtil
import com.close.hook.ads.preference.HookPrefs
import com.google.common.cache.CacheBuilder
import de.robv.android.xposed.XposedBridge
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit

object NativeRequestHook {

    private const val LOG_PREFIX = "[NativeRequestHook] "
    private var isInitialized = false

    private val JAVA_NET_KEYWORDS = setOf(
        "libjavacrypto.so", "libopenjdk.so",
        "SocketOutputStream_socketWrite0", "NET_Send", "NET_Read"
    )

    private const val MAX_BUFFER_SIZE = 5 * 1024 * 1024

    private val h2RequestBuffers = CacheBuilder.newBuilder()
        .expireAfterAccess(3, TimeUnit.MINUTES)
        .build<String, ByteArrayOutputStream>()
    
    private val h2ResponseBuffers = CacheBuilder.newBuilder()
        .expireAfterAccess(3, TimeUnit.MINUTES)
        .build<String, ByteArrayOutputStream>()

    fun init(enableNativeHook: Boolean) {
        if (isInitialized) return
        try {
            System.loadLibrary("native_hook")
            initNativeHook(enableNativeHook)
            isInitialized = true
            XposedBridge.log("$LOG_PREFIX Native library loaded. Interception: $enableNativeHook")
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX Failed to load native library: ${e.message}")
        }
    }

    private external fun initNativeHook(enableNativeHook: Boolean)
    external fun feedH2Data(connId: Long, isLocal: Boolean, data: ByteArray, offset: Int, length: Int, collectRespBody: Boolean): Int
    external fun freeH2Conn(connId: Long)

    @JvmStatic
    fun onNativeData(
        id: Long,
        isWrite: Boolean,
        buffer: ByteBuffer?,
        address: String?,
        stack: String?,
        isSSL: Boolean
    ): Boolean {
        if (buffer == null || !buffer.hasRemaining()) return false

        val dup = buffer.duplicate()
        val data = ByteArray(dup.remaining())
        dup.get(data)

        return if (isSSL) {
            processSSLData(id, isWrite, data, address, stack)
        } else {
            processTcpData(id.toInt(), isWrite, data, address, stack)
        }
    }

    private fun processSSLData(sslId: Long, isWrite: Boolean, data: ByteArray, address: String?, stack: String?): Boolean {
        val key = sslId.hashCode()
        var shouldBlock = false

        val buffers = if (isWrite) RequestHook.requestBuffers else RequestHook.responseBuffers
        val buffer = (buffers as ConcurrentMap<Int, ByteArrayOutputStream>).computeIfAbsent(key) { ByteArrayOutputStream() }

        try {
            synchronized(buffer) {
                if (buffer.size() + data.size <= MAX_BUFFER_SIZE) {
                    buffer.write(data)
                    
                    if (isWrite) {
                        RequestHook.processRequestBuffer(key, isHttps = true)
                        val requestInfo = RequestHook.pendingRequests[key]
                        if (requestInfo != null) {
                            val javaStack = HookUtil.getFormattedStackTrace()
                            val isFromJavaNetworking = stack?.let { nativeStack ->
                                JAVA_NET_KEYWORDS.any { keyword -> nativeStack.contains(keyword) }
                            } ?: false

                            val finalStack = if (isFromJavaNetworking || stack == null) javaStack else "$stack\n$javaStack"
                            val enrichedInfo = requestInfo.copy(
                                requestType = " NATIVE-SSL",
                                fullAddress = address ?: requestInfo.fullAddress,
                                stack = finalStack
                            )
                            RequestHook.pendingRequests[key] = enrichedInfo
                            shouldBlock = RequestHook.checkShouldBlockRequest(enrichedInfo)
                            if (shouldBlock) RequestHook.pendingRequests.remove(key)
                        }
                    } else {
                        if (RequestHook.processResponseBuffer(key, null)) shouldBlock = true
                    }
                } else {
                    buffer.reset()
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX SSL processing error: ${e.message}")
        }
        return shouldBlock
    }

    private fun processTcpData(fd: Int, isWrite: Boolean, data: ByteArray, address: String?, stack: String?): Boolean {
        var shouldBlock = false
        val buffers = if (isWrite) RequestHook.requestBuffers else RequestHook.responseBuffers
        val buffer = (buffers as ConcurrentMap<Int, ByteArrayOutputStream>).computeIfAbsent(fd) { ByteArrayOutputStream() }

        try {
            synchronized(buffer) {
                if (buffer.size() + data.size <= MAX_BUFFER_SIZE) {
                    buffer.write(data)
                    
                    if (isWrite) {
                        RequestHook.processRequestBuffer(fd, isHttps = false)
                        val requestInfo = RequestHook.pendingRequests[fd]
                        if (requestInfo != null) {
                            val javaStack = HookUtil.getFormattedStackTrace()
                            val isFromJavaNetworking = stack?.let { nativeStack ->
                                JAVA_NET_KEYWORDS.any { keyword -> nativeStack.contains(keyword) }
                            } ?: false

                            val finalStack = if (isFromJavaNetworking || stack == null) javaStack else "$stack\n$javaStack"
                            val enrichedInfo = requestInfo.copy(
                                requestType = " NATIVE-TCP",
                                fullAddress = address ?: requestInfo.fullAddress,
                                stack = finalStack
                            )
                            RequestHook.pendingRequests[fd] = enrichedInfo
                            shouldBlock = RequestHook.checkShouldBlockRequest(enrichedInfo)
                            if (shouldBlock) RequestHook.pendingRequests.remove(fd)
                        }
                    } else {
                        if (RequestHook.processResponseBuffer(fd, null)) shouldBlock = true
                    }
                } else {
                    buffer.reset()
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX TCP processing error: ${e.message}")
        }
        return shouldBlock
    }

    @JvmStatic
    fun onH2DataChunk(connId: Long, streamId: Int, isWrite: Boolean, buffer: ByteBuffer?) {
        if (buffer == null || !buffer.hasRemaining()) return
        if (!isWrite && !getCollectResponseBody()) return

        val key = "${connId}_$streamId"
        val dup = buffer.duplicate()
        val data = ByteArray(dup.remaining())
        dup.get(data)

        val buffers = if (isWrite) h2RequestBuffers else h2ResponseBuffers
        val stream = buffers.get(key) { ByteArrayOutputStream() }
        
        synchronized(stream) {
            if (stream.size() + data.size <= MAX_BUFFER_SIZE) {
                stream.write(data)
            }
        }
    }

    @JvmStatic
    fun onH2Request(
        connId: Long, streamId: Int, method: String?, path: String?, authority: String?, scheme: String?, 
        reqHeaders: String?, respHeaders: String?, statusCode: Int, isComplete: Boolean
    ): Boolean {
        val url = buildUrl(scheme, authority, path)
        if (url.isEmpty()) return false

        var contentType: String? = null
        var contentEncoding: String? = null

        fun parseHeaders(raw: String?): String? {
            if (raw.isNullOrEmpty()) return null
            val parts = raw.trimEnd('\n').split('\n')
            if (parts.size < 2) return null
            val sb = StringBuilder()
            parts.chunked(2).forEach { (k, v) ->
                sb.append(k).append(": ").append(v).append("\n")
                if (k.equals("content-type", ignoreCase = true)) contentType = v
                if (k.equals("content-encoding", ignoreCase = true)) contentEncoding = v
            }
            return sb.toString().trimEnd('\n')
        }

        val parsedReqHeaders = parseHeaders(reqHeaders)
        val parsedRespHeaders = parseHeaders(respHeaders)
        val mimeType = if (!contentEncoding.isNullOrEmpty()) "$contentType; encoding=$contentEncoding" else contentType

        val key = "${connId}_$streamId"
        val reqBody = if (isComplete) h2RequestBuffers.asMap().remove(key)?.toByteArray() else null
        val respBody = if (isComplete) h2ResponseBuffers.asMap().remove(key)?.toByteArray() else null

        val info = BlockedRequest(
            requestType     = " H2",
            requestValue    = buildUrlWithoutQuery(scheme, authority, path),
            method          = method,
            urlString       = url,
            requestHeaders  = parsedReqHeaders,
            requestBody     = reqBody,
            responseCode    = statusCode,
            responseMessage = null,
            responseHeaders = parsedRespHeaders,
            responseBody    = respBody,
            responseBodyContentType = mimeType,
            stack           = HookUtil.getFormattedStackTrace(),
            dnsHost         = null,
            fullAddress     = null
        )
        return RequestHook.checkShouldBlockRequest(info)
    }

    @JvmStatic
    fun onConnectionClosed(id: Long, isSSL: Boolean) {
        val key = if (isSSL) id.hashCode() else id.toInt()
        RequestHook.requestBuffers.remove(key)
        RequestHook.responseBuffers.remove(key)
        RequestHook.pendingRequests.remove(key)
    }

    @JvmStatic
    fun getCollectResponseBody(): Boolean = HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)

    private fun buildUrl(scheme: String?, authority: String?, path: String?): String {
        val s = scheme?.ifEmpty { "https" } ?: "https"
        val a = authority ?: return ""
        val p = path ?: "/"
        return "$s://$a$p"
    }

    private fun buildUrlWithoutQuery(scheme: String?, authority: String?, path: String?): String {
        val s = scheme?.ifEmpty { "https" } ?: "https"
        val a = authority ?: return ""
        val p = (path ?: "/").substringBefore('?')
        return "$s://$a$p"
    }
}
