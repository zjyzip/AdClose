package com.close.hook.ads.hook.gc.network

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.hook.util.HookUtil
import com.close.hook.ads.hook.util.StringFinderKit
import com.close.hook.ads.preference.HookPrefs
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Field
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.ByteBuffer
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLParameters

internal object RequestHookHandler {

    private const val LOG_PREFIX = "[RequestHookHandler] "
    private lateinit var applicationContext: Context

    private val okhttp3ResponseBodyClass: Class<*>? by lazy {
        try {
            XposedHelpers.findClass("okhttp3.ResponseBody", applicationContext.classLoader)
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX ${e.message}")
            null
        }
    }

    private val okioBufferClass: Class<*>? by lazy {
        try {
            XposedHelpers.findClass("okio.Buffer", applicationContext.classLoader)
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

    fun init(context: Context) {
        applicationContext = context
        setupDNSRequestHook()
        setupSocketHook() // HTTP/1.1
        setupConscryptEngineHook() // HTTPS over HTTP/1.1
        setupOkHttpRequestHook()
        // setupProtocolDowngradeHook()
        setupWebViewRequestHook()
        setupCronetRequestHook() // ByteDance
    }

    private fun setupDNSRequestHook() {
        HookUtil.findAndHookMethod(
            InetAddress::class.java,
            "getByName",
            arrayOf(String::class.java),
            "after"
        ) { param ->
            if (RequestHook.processDnsRequest(param.args[0], param.result)) {
                param.result = null
            }
        }
        HookUtil.findAndHookMethod(
            InetAddress::class.java,
            "getAllByName",
            arrayOf(String::class.java),
            "after"
        ) { param ->
            if (RequestHook.processDnsRequest(param.args[0], param.result)) {
                param.result = emptyArray<InetAddress>()
            }
        }
    }

    private fun setupSocketHook() {
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
                val buffer = RequestHook.requestBuffers.getOrPut(key) { ByteArrayOutputStream() }
                buffer.write(bytes, offset, len)
                RequestHook.processRequestBuffer(key, false)  // isHttps = false
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
                val buffer = RequestHook.responseBuffers.getOrPut(key) { ByteArrayOutputStream() }
                buffer.write(bytes, 0, len)
                RequestHook.processResponseBuffer(key, param)
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
                        val buffer = RequestHook.requestBuffers.getOrPut(key) { ByteArrayOutputStream() }

                        val position = srcBuffer.position()
                        val bytes = ByteArray(srcBuffer.remaining())
                        srcBuffer.get(bytes)
                        srcBuffer.position(position)

                        buffer.write(bytes)
                        RequestHook.processRequestBuffer(key, true)  // isHttps = true
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
                    val result = param.result as? SSLEngineResult ?: return@findAndHookMethod
                    val dstBuffer = param.args[1] as ByteBuffer
                    val bytesProduced = result.bytesProduced()

                    if (bytesProduced > 0) {
                        val key = System.identityHashCode(param.thisObject)
                        val buffer = RequestHook.responseBuffers.getOrPut(key) { ByteArrayOutputStream() }

                        val position = dstBuffer.position()
                        val start = position - bytesProduced
                        val bytes = ByteArray(bytesProduced)
                        for (i in 0 until bytesProduced) {
                           bytes[i] = dstBuffer.get(start + i)
                        }

                        buffer.write(bytes)
                        RequestHook.processResponseBuffer(key, param)
                    }
                } catch (e: Throwable) {
                    XposedBridge.log("$LOG_PREFIX ConscryptEngine.unwrap hook error: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX Error setting up ConscryptEngine hook: ${e.message}")
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
                                val bufferClass = okioBufferClass ?: return@let null
                                val bufferInstance = bufferClass.getDeclaredConstructor().newInstance()
                                XposedHelpers.callMethod(requestBody, "writeTo", bufferInstance)
                                XposedHelpers.callMethod(bufferInstance, "readByteArray") as? ByteArray
                            }
                        } catch (e: Throwable) {
                            // XposedBridge.log("$LOG_PREFIX OkHttp request body reading failed (likely due to obfuscation): ${e.message}")
                            null
                        }

                        var responseBodyBytes: ByteArray? = null
                        var mimeTypeWithEncoding: String? = null

                        if (HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)) {
                            try {
                                val originalBody = XposedHelpers.callMethod(response, "body")
                                if (originalBody != null) {
                                    val mediaType = XposedHelpers.callMethod(originalBody, "contentType")
                                    val responseContentType = mediaType?.toString()
                                    responseBodyBytes = XposedHelpers.callMethod(originalBody, "bytes") as? ByteArray

                                    val contentEncoding = XposedHelpers.callMethod(response, "header", "Content-Encoding") as? String

                                    mimeTypeWithEncoding = if (!contentEncoding.isNullOrEmpty()) {
                                        "$responseContentType; encoding=$contentEncoding"
                                    } else {
                                        responseContentType
                                    }

                                    if (responseBodyBytes != null && okhttp3ResponseBodyClass != null) {
                                        val newBody = XposedHelpers.callStaticMethod(okhttp3ResponseBodyClass, "create", mediaType, responseBodyBytes)
                                        XposedHelpers.setObjectField(response, "body", newBody)
                                    }
                                }
                            } catch (e: Throwable) {
                                XposedBridge.log("$LOG_PREFIX OkHttp response body reading failed: ${e.message}")
                            }
                        }

                        val info = buildOkHttpRequest(
                            url, " OKHTTP", request, response,
                            requestBodyBytes,
                            responseBodyBytes, mimeTypeWithEncoding, stackTrace
                        )
                        if (RequestHook.checkShouldBlockRequest(info)) {
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
            val formattedUrl = RequestHook.formatUrlWithoutQuery(url)
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

    private fun setupWebViewRequestHook() {
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

        HookUtil.hookAllMethods(
            clientClassName,
            "shouldInterceptRequest",
            "after",
            { param ->
                if (param.result != null) return@hookAllMethods

                if (param.args.size != 2) return@hookAllMethods
                val request = param.args[1] as? WebResourceRequest ?: return@hookAllMethods

                if (processWebRequest(request, false)) {
                    param.result = emptyWebResponse
                    return@hookAllMethods
                }

                if (HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)) {
                    param.result = executeAndProxyRequest(request)
                }
            },
            classLoader
        )

        HookUtil.hookAllMethods(
            clientClassName,
            "shouldOverrideUrlLoading",
            "before",
            { param ->
                if (param.args.size != 2) return@hookAllMethods
                if (processWebRequest(param.args[1], false)) {
                    param.result = true
                }
            },
            classLoader
        )
    }

    private fun executeAndProxyRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url == null || (url.scheme != "http" && url.scheme != "https")) {
            return null
        }

        try {
            val urlConnection = URL(request.url.toString()).openConnection() as HttpURLConnection
            urlConnection.requestMethod = request.method
            request.requestHeaders.forEach { (key, value) ->
                urlConnection.setRequestProperty(key, value)
            }
            urlConnection.connect()

            val responseCode = urlConnection.responseCode
            val responseMessage = urlConnection.responseMessage
            val responseHeaders = urlConnection.headerFields.mapValues { it.value.joinToString(", ") }

            val inputStream = try { urlConnection.inputStream } catch (e: IOException) { urlConnection.errorStream } ?: return null

            val responseBodyBytes = RequestHook.readStream(inputStream)

            val info = BlockedRequest(
                requestType = " Web",
                requestValue = RequestHook.formatUrlWithoutQuery(request.url),
                method = request.method,
                urlString = request.url.toString(),
                requestHeaders = request.requestHeaders.toString(),
                requestBody = null,
                responseCode = responseCode,
                responseMessage = responseMessage,
                responseHeaders = responseHeaders.toString(),
                responseBody = responseBodyBytes,
                responseBodyContentType = urlConnection.contentType,
                stack = HookUtil.getFormattedStackTrace(),
                dnsHost = null,
                fullAddress = null
            )
            RequestHook.checkShouldBlockRequest(info)

            val mimeType = responseHeaders["content-type"]?.split(";")?.firstOrNull()?.trim()
            val encoding = responseHeaders["content-encoding"]?.trim()

            return WebResourceResponse(
                mimeType,
                encoding,
                responseCode,
                responseMessage,
                responseHeaders,
                ByteArrayInputStream(responseBodyBytes)
            )

        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX Error proxying WebView request: ${e.message}")
            return null
        }
    }

    private fun processWebRequest(request: Any?, proxy: Boolean): Boolean {
        try {
            val webResourceRequest = request as? WebResourceRequest ?: return false
            val urlString = webResourceRequest.url?.toString() ?: return false
            val formattedUrl = RequestHook.formatUrlWithoutQuery(Uri.parse(urlString))

            val info = BlockedRequest(
                requestType = " Web",
                requestValue = formattedUrl,
                method = webResourceRequest.method,
                urlString = urlString,
                requestHeaders = webResourceRequest.requestHeaders?.toString(),
                requestBody = null,
                responseCode = -1,
                responseMessage = null,
                responseHeaders = null,
                responseBody = null,
                responseBodyContentType = null,
                stack = if (proxy) null else HookUtil.getFormattedStackTrace(),
                dnsHost = null,
                fullAddress = null
            )
            return RequestHook.checkShouldBlockRequest(info)
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

    private fun setupCronetRequestHook() {
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

                    if (requestObject == null || responseInfoObject == null) return@hookAllMethods

                    var initialUrl: String? = null // mInitialUrl
                    var method: String? = null // mInitialMethod
                    var requestHeaders: String? = null // mRequestHeaders

                    requestObject.javaClass.declaredFields.forEach { field ->
                        field.isAccessible = true
                        when (val value = field.get(requestObject)) {
                            is String -> {
                                if (value.startsWith("http") && initialUrl == null) initialUrl = value
                                else if ((value.equals("GET", true) || value.equals("POST", true)) && method == null) method = value
                            }
                            is List<*> -> if (requestHeaders == null) requestHeaders = value.toString()
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
                                    (buffer as? ByteBuffer)?.takeIf { it.hasArray() }?.let { responseBody = it.array() }
                                }
                            } catch (e: Throwable) {
                                XposedBridge.log("$LOG_PREFIX Cronet buffer field access error: ${e.message}")
                            }

                            if (responseBody == null) {
                                (streamObj as? InputStream)?.use { inputStream ->
                                    responseBody = RequestHook.readStream(inputStream)
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
                    val formattedUrl = finalUrl?.let { RequestHook.formatUrlWithoutQuery(Uri.parse(it)) }

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

                    if (RequestHook.checkShouldBlockRequest(info)) {
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
}
