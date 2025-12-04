package com.close.hook.ads.hook.gc.network

import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.hook.util.HookUtil
import de.robv.android.xposed.XposedBridge
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

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
        id: Int,
        isWrite: Boolean,
        data: ByteArray?,
        address: String?,
        stack: String?,
        isSSL: Boolean
    ): Boolean {
        if (data == null || data.isEmpty()) return false

        val key = if (isSSL) id else -id
        
        var shouldBlock = false

        if (isWrite) {
            val buffer = RequestHook.requestBuffers.getOrPut(key) { ByteArrayOutputStream() }
            try {
                buffer.write(data)
                RequestHook.processRequestBuffer(key, isSSL)
                
                val requestInfo = RequestHook.pendingRequests[key]
                if (requestInfo != null) {
                    
                    val javaStack = HookUtil.getFormattedStackTrace()
                    
                    val isFromJavaNetworking = stack?.let { nativeStack ->
                        JAVA_NET_KEYWORDS.any { keyword -> nativeStack.contains(keyword) }
                    } ?: false

                    val finalStack = if (isFromJavaNetworking) {
                        javaStack
                    } else {
                        stack ?: javaStack
                    }
                    
                    val enrichedInfo = requestInfo.copy(
                        requestType = if (isSSL) " NATIVE-SSL" else " NATIVE-TCP",
                        fullAddress = address ?: requestInfo.fullAddress,
                        stack = finalStack
                    )
                    
                    RequestHook.pendingRequests[key] = enrichedInfo

                    shouldBlock = RequestHook.checkShouldBlockRequest(enrichedInfo)
                    if (shouldBlock) {
                        RequestHook.pendingRequests.remove(key)
                    }
                }
            } catch (e: Exception) {
                XposedBridge.log("$LOG_PREFIX Error processing request buffer: ${e.message}")
            }
        } else {
            val buffer = RequestHook.responseBuffers.getOrPut(key) { ByteArrayOutputStream() }
            try {
                buffer.write(data)
                if (RequestHook.processResponseBuffer(key, null)) {
                    shouldBlock = true
                }
            } catch (e: Exception) {
                XposedBridge.log("$LOG_PREFIX Error processing response buffer: ${e.message}")
            }
        }
        
        return shouldBlock
    }
}
