package com.close.hook.ads.hook.gc.network

import com.close.hook.ads.hook.util.HookUtil
import de.robv.android.xposed.XposedBridge
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

object NativeRequestHook {

    private const val LOG_PREFIX = "[NativeRequestHook] "
    private var isInitialized = false

    private val JAVA_NET_KEYWORDS = setOf(
        "libjavacrypto.so",
        "libopenjdk.so",
        "SocketOutputStream_socketWrite0",
        "NET_Send",
        "NET_Read"
    )

    private val sslRequestBuffers = ConcurrentHashMap<Int, ByteArrayOutputStream>()
    private val sslResponseBuffers = ConcurrentHashMap<Int, ByteArrayOutputStream>()

    fun init() {
        if (isInitialized) return
        try {
            System.loadLibrary("native_hook")
            initNativeHook()
            isInitialized = true
            XposedBridge.log("$LOG_PREFIX Native hook initialized successfully.")
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX Failed to load native library: ${e.message}")
        }
    }

    private external fun initNativeHook()

    @JvmStatic
    fun onNativeData(
        id: Long,
        isWrite: Boolean,
        data: ByteArray?,
        address: String?,
        stack: String?,
        isSSL: Boolean
    ): Boolean {
        if (data == null || data.isEmpty()) return false

        return if (isSSL) {
            processSSLData(id, isWrite, data, address, stack)
        } else {
            processTcpData(id.toInt(), isWrite, data, address, stack)
        }
    }

    private fun processSSLData(
        sslId: Long,
        isWrite: Boolean,
        data: ByteArray,
        address: String?,
        stack: String?
    ): Boolean {
        val key = (sslId xor (sslId ushr 32)).toInt().let {
            if (it >= 0) it.inv() else it
        }

        var shouldBlock = false

        if (isWrite) {
            val buffer = sslRequestBuffers.computeIfAbsent(key) { ByteArrayOutputStream() }
            try {
                buffer.write(data)
                RequestHook.processRequestBuffer(key, isHttps = true)

                val requestInfo = RequestHook.pendingRequests[key]
                if (requestInfo != null) {
                    val javaStack = HookUtil.getFormattedStackTrace()
                    val isFromJavaNetworking = stack?.let { nativeStack ->
                        JAVA_NET_KEYWORDS.any { keyword -> nativeStack.contains(keyword) }
                    } ?: false
                    val finalStack = if (isFromJavaNetworking || stack == null) javaStack
                                     else "$stack\n$javaStack"

                    val enrichedInfo = requestInfo.copy(
                        requestType = " NATIVE-SSL",
                        fullAddress = address ?: requestInfo.fullAddress,
                        stack = finalStack
                    )
                    RequestHook.pendingRequests[key] = enrichedInfo
                    shouldBlock = RequestHook.checkShouldBlockRequest(enrichedInfo)
                    if (shouldBlock) RequestHook.pendingRequests.remove(key)
                }
            } catch (e: Exception) {
                XposedBridge.log("$LOG_PREFIX Error processing SSL request buffer: ${e.message}")
            }
        } else {
            val buffer = sslResponseBuffers.computeIfAbsent(key) { ByteArrayOutputStream() }
            try {
                buffer.write(data)
                if (RequestHook.processResponseBuffer(key, null)) shouldBlock = true
            } catch (e: Exception) {
                XposedBridge.log("$LOG_PREFIX Error processing SSL response buffer: ${e.message}")
            }
        }

        return shouldBlock
    }

    private fun processTcpData(
        fd: Int,
        isWrite: Boolean,
        data: ByteArray,
        address: String?,
        stack: String?
    ): Boolean {
        var shouldBlock = false

        if (isWrite) {
            val buffer = RequestHook.requestBuffers.computeIfAbsent(fd) { ByteArrayOutputStream() }
            try {
                buffer.write(data)
                RequestHook.processRequestBuffer(fd, isHttps = false)

                val requestInfo = RequestHook.pendingRequests[fd]
                if (requestInfo != null) {
                    val javaStack = HookUtil.getFormattedStackTrace()
                    val isFromJavaNetworking = stack?.let { nativeStack ->
                        JAVA_NET_KEYWORDS.any { keyword -> nativeStack.contains(keyword) }
                    } ?: false
                    val finalStack = if (isFromJavaNetworking || stack == null) javaStack
                                     else "$stack\n$javaStack"

                    val enrichedInfo = requestInfo.copy(
                        requestType = " NATIVE-TCP",
                        fullAddress = address ?: requestInfo.fullAddress,
                        stack = finalStack
                    )
                    RequestHook.pendingRequests[fd] = enrichedInfo
                    shouldBlock = RequestHook.checkShouldBlockRequest(enrichedInfo)
                    if (shouldBlock) RequestHook.pendingRequests.remove(fd)
                }
            } catch (e: Exception) {
                XposedBridge.log("$LOG_PREFIX Error processing TCP request buffer: ${e.message}")
            }
        } else {
            val buffer = RequestHook.responseBuffers.computeIfAbsent(fd) { ByteArrayOutputStream() }
            try {
                buffer.write(data)
                if (RequestHook.processResponseBuffer(fd, null)) shouldBlock = true
            } catch (e: Exception) {
                XposedBridge.log("$LOG_PREFIX Error processing TCP response buffer: ${e.message}")
            }
        }

        return shouldBlock
    }
}
