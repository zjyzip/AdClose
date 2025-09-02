package com.close.hook.ads.hook.gc.network

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Field
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

object RequestHook {

    private const val LOG_PREFIX = "[RequestHook] "
    private val UTF8: Charset = StandardCharsets.UTF_8

    private val emptyWebResponse: WebResourceResponse? by lazy { createEmptyWebResourceResponse() }

    private val dnsHostCache: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val urlStringCache: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private lateinit var applicationContext: Context

    private val okHttp3OkioBufferCls: Class<*>? by lazy {
        try {
            XposedHelpers.findClass("okio.Buffer", applicationContext.classLoader)
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX okio.Buffer class not found: ${e.message}")
            null
        }
    }

    private val httpRetryableSinkCls: Class<*>? by lazy {
        try {
            XposedHelpers.findClass("com.android.okhttp.internal.http.RetryableSink", applicationContext.classLoader)
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX com.android.okhttp.internal.http.RetryableSink class not found: ${e.message}")
            null
        }
    }

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
                    setupHttpRequestHook()
                    setupOkHttpRequestHook()
                    setupWebViewRequestHook()
                    
                    // 受严重混淆问题，只做了某音的抓取。对于org.chromium.net类的Hook点需调整至onSucceeded/onResponseStarted，getResponse貌似不行(YouTube/PlayStote)，未进行更多的测试，弃坑。
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

    private fun readHttpRequestBody(httpEngine: Any): ByteArray? {
        val localHttpRetryableSinkCls = httpRetryableSinkCls ?: return null
        
        try {
            val sink = XposedHelpers.getObjectField(httpEngine, "requestBodyOut")
            if (localHttpRetryableSinkCls.isInstance(sink)) {
                val contentBuffer = XposedHelpers.getObjectField(sink, "content")
                if ((XposedHelpers.callMethod(contentBuffer, "size") as Long) > 0) {
                    val clonedBuffer = XposedHelpers.callMethod(contentBuffer, "clone")
                    val bytes = XposedHelpers.callMethod(clonedBuffer, "readByteArray") as ByteArray

                    return bytes
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX Error reading HTTP request body from RetryableSink: ${e.message}")
        }
        return null
    }
    
    private fun readOkHttp3RequestBody(request: Any): ByteArray? {
        val requestBody = XposedHelpers.callMethod(request, "body") ?: return null
        val localOkHttp3OkioBufferCls = okHttp3OkioBufferCls ?: return null

        return try {
            val buffer = localOkHttp3OkioBufferCls.newInstance()
            XposedHelpers.callMethod(requestBody, "writeTo", buffer)
            XposedHelpers.callMethod(buffer, "readByteArray") as? ByteArray
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX Error reading OkHttp3 request body: ${e.message}")
            null
        }
    }

    private fun checkShouldBlockRequest(info: RequestInfo?): Boolean {
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

        val info = RequestInfo(
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

    private fun setupHttpRequestHook() {
        HookUtil.hookAllMethods(
            "com.android.okhttp.internal.http.HttpEngine",
            "readResponse",
            "after"
        ) { param ->
            try {
                val httpEngine = param.thisObject
                val userResponse = XposedHelpers.getObjectField(httpEngine, "userResponse") ?: return@hookAllMethods
                val request = XposedHelpers.getObjectField(httpEngine, "userRequest") ?: return@hookAllMethods
                val url = URL(XposedHelpers.callMethod(request, "urlString").toString())
                val stackTrace = HookUtil.getFormattedStackTrace()

                val requestBodyBytes = readHttpRequestBody(httpEngine)

                var responseBodyBytes: ByteArray? = null
                var responseContentType: String? = null
                if (HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)) {
                    try {
                        XposedHelpers.callMethod(userResponse, "body")?.let { originalResponseBody ->
                            val mediaTypeObj = XposedHelpers.callMethod(originalResponseBody, "contentType")
                            responseContentType = mediaTypeObj?.toString()
                            XposedHelpers.callMethod(originalResponseBody, "source")?.let { source ->
                                XposedHelpers.callMethod(source, "request", Long.MAX_VALUE)
                                XposedHelpers.callMethod(source, "buffer")?.let { buffer ->
                                    val clonedBuffer = XposedHelpers.callMethod(buffer, "clone")
                                    responseBodyBytes = XposedHelpers.callMethod(clonedBuffer, "readByteArray") as? ByteArray
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        XposedBridge.log("$LOG_PREFIX Android OkHttp non-destructive body reading error: ${e.message}")
                    }
                }

                val info = buildRequestInfo(
                    url, " HTTP", request, userResponse,
                    requestBodyBytes,
                    responseBodyBytes, responseContentType, stackTrace
                )
                if (checkShouldBlockRequest(info)) {
                    createEmptyResponseForHttp(userResponse)?.let { emptyResponse ->
                        XposedHelpers.setObjectField(httpEngine, "userResponse", emptyResponse)
                    }
                }
            } catch (e: Exception) {
                XposedBridge.log("$LOG_PREFIX HTTP hook (HttpEngine) error: ${e.message}")
            }
        }
    }

    private fun createEmptyResponseForHttp(response: Any?): Any? {
        if (response == null) return null
        return try {
            val builderClass = Class.forName("com.android.okhttp.Response\$Builder")
            val protocolClass = Class.forName("com.android.okhttp.Protocol")
            val responseBodyClass = Class.forName("com.android.okhttp.ResponseBody")
            val mediaTypeClass = Class.forName("com.android.okhttp.MediaType")
            val request = XposedHelpers.callMethod(response, "request")
            val protocolHTTP11 = XposedHelpers.getStaticObjectField(protocolClass, "HTTP_1_1")
            val builder = builderClass.getDeclaredConstructor().newInstance().apply {
                XposedHelpers.callMethod(this, "request", request)
                XposedHelpers.callMethod(this, "protocol", protocolHTTP11)
                XposedHelpers.callMethod(this, "code", 204)
                XposedHelpers.callMethod(this, "message", "No Content")
            }
            val createMethod = XposedHelpers.findMethodExact(responseBodyClass, "create", mediaTypeClass, String::class.java)
            val emptyResponseBody = createMethod.invoke(null, null, "")
            XposedHelpers.callMethod(builder, "body", emptyResponseBody)
            XposedHelpers.callMethod(builder, "build")
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX createEmptyResponseForHttp error: ${e.message}")
            null
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

                        val requestBodyBytes = readOkHttp3RequestBody(request)
                        
                        var responseBodyBytes: ByteArray? = null
                        var responseContentType: String? = null
                        if (HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)) {
                            XposedHelpers.callMethod(response, "body")?.let { responseBody ->
                                try {
                                    responseContentType = XposedHelpers.callMethod(responseBody, "contentType")?.toString()
                                    XposedHelpers.callMethod(responseBody, "source")?.let { source ->
                                        XposedHelpers.callMethod(source, "request", Long.MAX_VALUE)
                                        XposedHelpers.callMethod(source, "buffer")?.let { buffer ->
                                            responseBodyBytes = XposedHelpers.callMethod(XposedHelpers.callMethod(buffer, "clone"), "readByteArray") as? ByteArray
                                        }
                                    }
                                } catch (e: Throwable) {
                                    XposedBridge.log("$LOG_PREFIX OkHttp3 non-destructive body reading error: ${e.message}")
                                }
                            }
                        }
                        val info = buildRequestInfo(
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

    private fun buildRequestInfo(
        url: URL, requestFrameworkType: String, request: Any, response: Any,
        requestBody: ByteArray?,
        responseBody: ByteArray?, responseBodyContentType: String?, stack: String
    ): RequestInfo? {
        return try {
            val method = XposedHelpers.callMethod(request, "method") as? String
            val urlString = url.toString()
            val requestHeaders = XposedHelpers.callMethod(request, "headers")?.toString()
            val code = XposedHelpers.callMethod(response, "code") as? Int ?: -1
            val message = XposedHelpers.callMethod(response, "message") as? String
            val responseHeaders = XposedHelpers.callMethod(response, "headers")?.toString()
            val formattedUrl = formatUrlWithoutQuery(url)
            RequestInfo(
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
            XposedBridge.log("$LOG_PREFIX buildRequestInfo error: ${e.message}")
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
            
            val info = RequestInfo(
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
                                // mBuffer
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

                    val info = RequestInfo(
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


    private fun sendBroadcast(info: RequestInfo, shouldBlock: Boolean, blockRuleType: String?, ruleUrl: String?) {
        sendBlockedRequestBroadcast("all", info, shouldBlock, ruleUrl, blockRuleType)
        sendBlockedRequestBroadcast(if (shouldBlock) "block" else "pass", info, shouldBlock, ruleUrl, blockRuleType)
    }

    private fun sendBlockedRequestBroadcast(type: String, info: RequestInfo, isBlocked: Boolean, ruleUrl: String?, blockRuleType: String?) {
        val added = info.dnsHost?.takeIf { it.isNotBlank() }?.let(dnsHostCache::add)
            ?: info.urlString?.takeIf { it.isNotBlank() }?.let(urlStringCache::add)
        if (added == false) return

        try {
            var requestBodyUriString: String? = null
            var responseBodyUriString: String? = null
            var responseBodyContentType: String? = null

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

            info.requestBody?.let {
                requestBodyUriString = storeBody(it, "text/plain")
            }
            info.responseBody?.let {
                responseBodyUriString = storeBody(it, info.responseBodyContentType)
                responseBodyContentType = info.responseBodyContentType
            }

            val blockedRequest = BlockedRequest(
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
                responseBodyContentType = responseBodyContentType,
                stack = info.stack,
                dnsHost = info.dnsHost,
                fullAddress = info.fullAddress
            )
            Intent("com.rikkati.REQUEST").apply {
                putExtra("request", blockedRequest)
            }.also {
                applicationContext.sendBroadcast(it)
            }
        } catch (e: Exception) {
            Log.w(LOG_PREFIX, "Broadcast send error.", e)
        }
    }
}
