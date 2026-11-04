package com.close.hook.ads.hook.network

import android.os.Process
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.hook.util.HookUtil
import com.close.hook.ads.preference.HookPrefs
import de.robv.android.xposed.XposedBridge
import java.nio.ByteBuffer

object NativeRequestHook {
    private const val LOG_PREFIX = "[NativeRequestHook]"
    private const val MAX_BUFFER_SIZE = 5 * 1024 * 1024
    @Volatile private var isInitialized = false
    @Volatile var nativeInterceptionActive: Boolean = false
        private set
    private data class NativeConnectionKey(val id: Long, val ssl: Boolean)

    private val bodies = H2BodyStore()

    @Synchronized
    fun init(enableNativeHook: Boolean) {
        if (isInitialized && (!enableNativeHook || nativeInterceptionActive)) return
        try {
            if (!isInitialized) System.loadLibrary("native_hook")
            nativeInterceptionActive = initNativeHook(enableNativeHook)
            isInitialized = true
            XposedBridge.log("$LOG_PREFIX HTTP/2 decoder ready. Native interception requested: $enableNativeHook")
        } catch (error: Throwable) {
            XposedBridge.log("$LOG_PREFIX Failed to load native library: $error")
        }
    }

    private external fun initNativeHook(enableNativeHook: Boolean): Boolean
    private external fun setJavaCaptureActive(active: Boolean)

    internal fun beginJavaCapture(): Boolean {
        if (!isInitialized) return false
        return runCatching { setJavaCaptureActive(true); true }.getOrDefault(false)
    }

    internal fun endJavaCapture() {
        if (isInitialized) runCatching { setJavaCaptureActive(false) }
    }
    external fun feedH2Data(connId: Long, isLocal: Boolean, data: ByteArray, offset: Int, length: Int, collectRespBody: Boolean): Int
    external fun freeH2Conn(connId: Long)

    fun feedH2DataIfAvailable(connId: Long, isLocal: Boolean, data: ByteArray, collectBody: Boolean): Int {
        if (!isInitialized) return 0
        return try { feedH2Data(connId, isLocal, data, 0, data.size, collectBody) }
        catch (_: UnsatisfiedLinkError) { 0 }
    }

    fun closeJavaConnection(connId: Long) {
        if (isInitialized) runCatching { freeH2Conn(connId) }
        bodies.closeConnection(connId)
    }

    @JvmStatic
    fun onNativeData(id: Long, isWrite: Boolean, buffer: ByteBuffer?, address: String?, stack: String?, isSSL: Boolean): Boolean {
        if (buffer == null || !buffer.hasRemaining() || buffer.remaining() > MAX_BUFFER_SIZE) return false
        val bytes = ByteArray(buffer.remaining())
        buffer.duplicate().get(bytes)
        val key = NativeConnectionKey(id, isSSL)
        return if (isWrite) {
            RequestHook.processRequestData(key, bytes, isHttps = isSSL,
                source = if (isSSL) " NATIVE-SSL" else " NATIVE-TCP", address = address, nativeStack = stack)
        } else RequestHook.processResponseData(key, bytes)
    }

    @JvmStatic
    fun onH2DataChunk(connId: Long, streamId: Int, isWrite: Boolean, buffer: ByteBuffer?) {
        if (buffer != null) bodies.append(connId, streamId, isWrite, buffer)
    }

    @JvmStatic
    fun onH2Request(
        connId: Long, streamId: Int, method: String?, path: String?, authority: String?, scheme: String?,
        reqHeaders: String?, respHeaders: String?, statusCode: Int, isComplete: Boolean,
        requestComplete: Boolean, responseComplete: Boolean
    ): Boolean {
        val requestHeaders = NetworkHeaders.alternating(reqHeaders)
        val host = authority?.takeIf(String::isNotBlank) ?: requestHeaders
            .firstOrNull { it.first.equals("host", true) }?.second
        val url = host?.let { "${scheme?.ifBlank { "https" } ?: "https"}://$it${path?.ifBlank { "/" } ?: "/"}" }
        val responseHeaders = NetworkHeaders.alternating(respHeaders)
        val contentType = responseHeaders.firstOrNull { it.first.equals("content-type", true) }?.second
            ?: "application/octet-stream"
        val encoding = responseHeaders.firstOrNull { it.first.equals("content-encoding", true) }?.second
        if (statusCode < 0 && !isComplete) bodies.begin(connId, streamId, getCollectResponseBody())
        val requestBody = if (requestComplete) bodies.takeBody(connId, streamId, true) else null
        val responseBody = if (responseComplete) bodies.takeBody(connId, streamId, false) else null
        val info = BlockedRequest(
            requestType = " H2", requestValue = url?.let(NetworkUrl::withoutQuery) ?: path.orEmpty().ifEmpty { "/" },
            method = method, urlString = url,
            requestHeaders = NetworkHeaders.format(requestHeaders), requestBody = requestBody,
            responseCode = statusCode, responseMessage = null, responseHeaders = NetworkHeaders.format(responseHeaders),
            responseBody = responseBody.takeIf { getCollectResponseBody() },
            responseBodyContentType = if (encoding.isNullOrBlank()) contentType else "$contentType; encoding=$encoding",
            stack = HookUtil.getFormattedStackTrace(), dnsHost = null, fullAddress = null,
            requestId = "${Process.myPid()}:h2:$connId:$streamId"
        )
        RequestHook.reportAllowedResponse(info)
        return false
    }

    @JvmStatic
    fun onConnectionClosed(id: Long, isSSL: Boolean) {
        RequestHook.closeConnection(NativeConnectionKey(id, isSSL))
        bodies.closeConnection(id)
    }

    @JvmStatic
    fun onConnectionDiscarded(id: Long, isSSL: Boolean) {
        RequestHook.discardConnection(NativeConnectionKey(id, isSSL))
        bodies.closeConnection(id)
    }

    @JvmStatic
    fun onH2StreamClosed(id: Long, streamId: Int) = bodies.close(id, streamId)

    @JvmStatic
    fun onH2ConnectionReset(id: Long) = bodies.closeConnection(id)

    @JvmStatic
    fun onH2BodyDiscarded(id: Long, streamId: Int, isWrite: Boolean) = bodies.discard(id, streamId, isWrite)

    @JvmStatic
    fun getCollectResponseBody(): Boolean = HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)
}
