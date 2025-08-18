package com.close.hook.ads.hook.gc.network

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.RequestInfo
import com.close.hook.ads.data.model.Url
import com.close.hook.ads.hook.util.ContextUtil
import com.close.hook.ads.hook.util.DexKitUtil
import com.close.hook.ads.hook.util.HookUtil
import com.close.hook.ads.hook.util.StringFinderKit
import com.close.hook.ads.provider.UrlContentProvider
import com.close.hook.ads.provider.ResponseBodyContentProvider
import com.close.hook.ads.util.AppUtils
import com.close.hook.ads.util.EncryptionUtil
import com.close.hook.ads.preference.HookPrefs
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.result.MethodData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object RequestHook {

    private const val LOG_PREFIX = "[RequestHook] "
    private val UTF8: Charset = StandardCharsets.UTF_8

    private var emptyWebResponse: Any? = null

    private val dnsHostCache = ConcurrentHashMap<String, Boolean>()
    private val urlStringCache = ConcurrentHashMap<String, Boolean>()

    private lateinit var applicationContext: Context
    private lateinit var hookPrefs: HookPrefs

    private var responseBodyCls: Class<*>? = null

    private var cronetHttpURLConnectionCls: Class<*>? = null // org.chromium.net.urlconnection.CronetHttpURLConnection
    private var urlResponseInfoCls: Class<*>? = null // org.chromium.net.ResponseInfo
    private var urlRequestCls: Class<*>? = null // org.chromium.net.UrlRequest
    private var cronetInputStreamCls: Class<*>? = null // org.chromium.net.urlconnection.CronetInputStream

    private val URL_CONTENT_URI: Uri = Uri.Builder()
        .scheme("content")
        .authority(UrlContentProvider.AUTHORITY)
        .appendPath(UrlContentProvider.URL_TABLE_NAME)
        .build()

    private val RESPONSE_BODY_CONTENT_URI: Uri = ResponseBodyContentProvider.CONTENT_URI

    private val queryCache: Cache<String, Triple<Boolean, String?, String?>> = CacheBuilder.newBuilder()
        .maximumSize(8192)
        .expireAfterAccess(4, TimeUnit.HOURS)
        .softValues()
        .build()

    fun init() {
        try {
            ContextUtil.addOnApplicationContextInitializedCallback {
                applicationContext = ContextUtil.applicationContext!!
                hookPrefs = HookPrefs.getXpInstance()
                setupDNSRequestHook()
                setupHttpRequestHook()
                setupOkHttpRequestHook()
                setupWebViewRequestHook()

                // 受严重混淆问题，只做了某音的抓取。对于org.chromium.net类的Hook点需调整至onSucceeded/onResponseStarted，getResponse貌似不行(YouTube/PlayStote)，未进行更多的测试，弃坑。
                if (applicationContext.packageName == "com.ss.android.ugc.aweme") {
                    setupCronetRequestHook()
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX Init error: ${e.message}")
        }
    }

    private fun formatUrlWithoutQuery(urlObject: Any?): String {
        try {
            return when (urlObject) {
                is URL -> {
                    val decodedPath = URLDecoder.decode(urlObject.path, UTF8.name())
                    URL(urlObject.protocol, urlObject.host, urlObject.port, decodedPath).toExternalForm()
                }
                is Uri -> {
                    val decodedPath = urlObject.path?.let { URLDecoder.decode(it, UTF8.name()) } ?: ""
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
            return urlObject?.toString() ?: ""
        }
    }

    private fun readStream(inputStream: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(4096)
        var nRead: Int
        while (inputStream.read(data, 0, data.size).also { nRead = it } != -1) {
            buffer.write(data, 0, nRead)
        }
        return buffer.toByteArray()
    }

    private fun checkShouldBlockRequest(info: RequestInfo?): Boolean {
        if (info == null) return false
        val queryTypes = listOf("URL", "Domain", "KeyWord")
        for (queryType in queryTypes) {
            val processedValue = if (queryType == "Domain") {
                AppUtils.extractHostOrSelf(info.requestValue)
            } else {
                info.requestValue
            }
            val matchResult = queryContentProvider(queryType, processedValue)
            if (matchResult.first) {
                sendBroadcast(info, true, matchResult.second, matchResult.third)
                return true
            }
        }
        sendBroadcast(info, false, null, null)
        return false
    }

    private fun queryContentProvider(queryType: String, queryValue: String): Triple<Boolean, String?, String?> {
        val cacheKey = "$queryType:$queryValue"
        queryCache.getIfPresent(cacheKey)?.let { return it }

        val result = performContentQuery(queryType, queryValue)
        queryCache.put(cacheKey, result)
        return result
    }

    private fun performContentQuery(queryType: String, queryValue: String): Triple<Boolean, String?, String?> {
        val contentResolver = applicationContext.contentResolver
        val projection = arrayOf(Url.URL_TYPE, Url.URL_ADDRESS)
        val selectionArgs = arrayOf(queryType, queryValue)
        try {
            contentResolver.query(URL_CONTENT_URI, projection, null, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val urlTypeIndex = cursor.getColumnIndexOrThrow(Url.URL_TYPE)
                    val urlAddressIndex = cursor.getColumnIndexOrThrow(Url.URL_ADDRESS)
                    val urlType = cursor.getString(urlTypeIndex)
                    val urlValue = cursor.getString(urlAddressIndex)
                    return Triple(true, urlType, urlValue)
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX Query error: ${e.message}")
        }
        return Triple(false, null, null)
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
        val fullAddress = when {
            methodName == "getByName" && result is InetAddress -> result.hostAddress
            methodName == "getAllByName" && result is Array<*> -> result.filterIsInstance<InetAddress>()
                .filter { it.hostAddress != null }
                .joinToString(", ") { it.hostAddress }
            else -> null
        }
        if (fullAddress.isNullOrEmpty()) return false
        val info = RequestInfo(" DNS", host, null, null, null, -1, null, null, null, null, stackTrace, host, fullAddress)
        return checkShouldBlockRequest(info)
    }

    private fun setupHttpRequestHook() {
        HookUtil.hookAllMethods(
            "com.android.okhttp.internal.huc.HttpURLConnectionImpl",
            "getResponse",
            "after",
            { param ->
                try {
                    val httpEngine = XposedHelpers.getObjectField(param.thisObject, "httpEngine")
                    if (httpEngine == null || !(XposedHelpers.callMethod(httpEngine, "hasResponse") as? Boolean ?: false)) {
                        return@hookAllMethods
                    }

                    var userResponse = XposedHelpers.getObjectField(httpEngine, "userResponse")
                    val request = XposedHelpers.callMethod(httpEngine, "getRequest")
                    val url = URL(XposedHelpers.callMethod(request, "urlString").toString())
                    val stackTrace = HookUtil.getFormattedStackTrace()

                    var responseBodyBytes: ByteArray? = null
                    var contentType: String? = null

                    if (hookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false) && userResponse != null) {
                        try {
                            val originalResponseBody = XposedHelpers.callMethod(userResponse, "body")
                            if (originalResponseBody != null) {
                                val mediaTypeObj = XposedHelpers.callMethod(originalResponseBody, "contentType")
                                contentType = mediaTypeObj?.toString()

                                val inputStream = XposedHelpers.callMethod(originalResponseBody, "byteStream") as? InputStream
                                inputStream?.use { responseBodyBytes = readStream(it) }

                                if (responseBodyBytes != null) {
                                    val responseBodyClass = XposedHelpers.findClass("com.android.okhttp.ResponseBody", applicationContext.classLoader)
                                    val mediaTypeClass = XposedHelpers.findClass("com.android.okhttp.MediaType", applicationContext.classLoader)
                                    val createMethod = XposedHelpers.findMethodExact(responseBodyClass, "create", mediaTypeClass, ByteArray::class.java)
                                    val newResponseBody = createMethod.invoke(null, mediaTypeObj, responseBodyBytes)

                                    val builder = XposedHelpers.callMethod(userResponse, "newBuilder")
                                    XposedHelpers.callMethod(builder, "body", newResponseBody)
                                    val newResponse = XposedHelpers.callMethod(builder, "build")
                                    
                                    XposedHelpers.setObjectField(httpEngine, "userResponse", newResponse)
                                    userResponse = newResponse
                                }
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("$LOG_PREFIX Android OkHttp body reading error: ${e.message}")
                        }
                    }

                    val info = buildRequestInfo(url, " HTTP", request, userResponse, responseBodyBytes, contentType, stackTrace)

                    if (checkShouldBlockRequest(info)) {
                        createEmptyResponseForHttp(userResponse)?.let { emptyResponse ->
                            XposedHelpers.setObjectField(httpEngine, "userResponse", emptyResponse)
                        }
                    }
                } catch (e: Exception) {
                    XposedBridge.log("$LOG_PREFIX HTTP hook error: ${e.message}")
                }
            }
        )
    }

    private fun createEmptyResponseForHttp(response: Any?): Any? {
        if (response == null || response::class.java.name != "com.android.okhttp.Response") {
            return null
        }

        val builderClass = Class.forName("com.android.okhttp.Response\$Builder")
        val requestClass = Class.forName("com.android.okhttp.Request")
        val protocolClass = Class.forName("com.android.okhttp.Protocol")
        val responseBodyClass = Class.forName("com.android.okhttp.ResponseBody")

        val request = XposedHelpers.callMethod(response, "request")

        val builder = builderClass.getDeclaredConstructor().newInstance()
        XposedHelpers.callMethod(builder, "request", request)

        val protocolHTTP11 = protocolClass.getDeclaredField("HTTP_1_1").get(null)
        XposedHelpers.callMethod(builder, "protocol", protocolHTTP11)

        XposedHelpers.callMethod(builder, "code", 204)
        XposedHelpers.callMethod(builder, "message", "No Content")

        val createMethod = XposedHelpers.findMethodExact(responseBodyClass, "create", Class.forName("com.android.okhttp.MediaType"), String::class.java)
        val emptyResponseBody = createMethod.invoke(null, null, "")

        XposedHelpers.callMethod(builder, "body", emptyResponseBody)

        return XposedHelpers.callMethod(builder, "build")
    }

    fun setupOkHttpRequestHook() {
        // okhttp3.Call.execute
        hookOkHttpMethod("setupOkHttpRequestHook_execute", "Already Executed", "execute")
        // okhttp3.internal.http.RetryAndFollowUpInterceptor.intercept
        hookOkHttpMethod("setupOkHttp2RequestHook_intercept", "Canceled", "intercept")

        try {
            responseBodyCls = XposedHelpers.findClass("okhttp3.ResponseBody", applicationContext.classLoader)
        } catch (e: Throwable) {
            XposedBridge.log("$LOG_PREFIX OkHttp load error: ${e.message}")
        }
    }

    private fun hookOkHttpMethod(cacheKeySuffix: String, methodDescription: String, methodName: String) {
        val cacheKey = "${applicationContext.packageName}:$cacheKeySuffix"
        val foundMethods = StringFinderKit.findMethodsWithString(cacheKey, methodDescription, methodName)

        foundMethods?.forEach { methodData ->
            try {
                val method = methodData.getMethodInstance(applicationContext.classLoader)
                XposedBridge.log("$LOG_PREFIX setupOkHttpRequestHook $methodData")

                HookUtil.hookMethod(method, "after") { param ->
                    try {
                        var response = param.result ?: return@hookMethod

                        val request = XposedHelpers.callMethod(response, "request")
                        val url = URL(XposedHelpers.callMethod(request, "url").toString())
                        val stackTrace = HookUtil.getFormattedStackTrace()

                        var bodyBytes: ByteArray? = null
                        var contentType: String? = null

                        if (hookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)) {
                            val responseBody = XposedHelpers.callMethod(response, "body")
                            if (responseBody != null) {
                                try {
                                    val mediaType = XposedHelpers.callMethod(responseBody, "contentType")
                                    contentType = mediaType?.toString()

                                    bodyBytes = XposedHelpers.callMethod(responseBody, "bytes") as? ByteArray

                                    if (bodyBytes != null) {
                                        val newResponseBody = XposedHelpers.callStaticMethod(responseBodyCls, "create", mediaType, bodyBytes)

                                        val newResponseBuilder = XposedHelpers.callMethod(response, "newBuilder")
                                        XposedHelpers.callMethod(newResponseBuilder, "body", newResponseBody)
                                        val newResponse = XposedHelpers.callMethod(newResponseBuilder, "build")

                                        param.result = newResponse
                                        response = newResponse
                                    }
                                } catch (e: Throwable) {
                                    XposedBridge.log("$LOG_PREFIX OkHttp body reading error: ${e.message}")
                                }
                            }
                        }

                        val info = buildRequestInfo(url, " OKHTTP", request, response, bodyBytes, contentType, stackTrace)

                        if (checkShouldBlockRequest(info)) {
                            param.throwable = IOException("Request blocked by AdClose")
                        }
                    } catch (e: IOException) {
                        param.throwable = e
                    } catch (e: Throwable) {
                        XposedBridge.log("$LOG_PREFIX OkHttp hook error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                XposedBridge.log("$LOG_PREFIX Error hooking OkHttp method: $methodData, ${e.message}")
            }
        }
    }

    private fun buildRequestInfo(
        url: URL, requestFrameworkType: String,
        request: Any, response: Any, responseBody: ByteArray?, contentType: String?, stack: String
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
                requestFrameworkType, formattedUrl, method, urlString,
                requestHeaders, code, message, responseHeaders, responseBody, contentType, stack, null, null
            )
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX Block check error: ${e.message}")
            null
        }
    }

    fun setupWebViewRequestHook() {
        if (emptyWebResponse == null) {
            emptyWebResponse = createEmptyWebResourceResponse()
        }
        HookUtil.findAndHookMethod(
            WebView::class.java,
            "setWebViewClient",
            arrayOf(WebViewClient::class.java),
            "before"
        ) { param ->
            val client = param.args[0]
            hookClientMethods(client.javaClass.name, applicationContext.classLoader)
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
                if (processWebRequest(param.args[1], null)) {
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
                if (processWebRequest(param.args[1], null)) {
                    param.result = true
                }
            },
            classLoader
        )
    }

    private fun processWebRequest(request: Any?, response: Any?): Boolean {
        try {
            var method: String? = null
            var urlString: String? = null
            var requestHeaders: String? = null
            if (request is WebResourceRequest) {
                method = request.method
                urlString = request.url.toString()
                requestHeaders = request.requestHeaders?.toString()
            }

            var responseCode = -1
            var responseMessage: String? = null
            var responseHeaders: String? = null
            var responseBody: ByteArray? = null
            var contentType: String? = null

            if (response is WebResourceResponse) {
                responseHeaders = response.responseHeaders?.toString()
                responseCode = response.statusCode
                responseMessage = response.reasonPhrase
                contentType = response.mimeType

                if (hookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false)) {
                    response.data?.use { inputStream ->
                        responseBody = readStream(inputStream)
                    }
                }
            }

            val stack = HookUtil.getFormattedStackTrace()
            val formattedUrl = urlString?.let { formatUrlWithoutQuery(Uri.parse(it)) }

            if (formattedUrl != null) {
                val info = RequestInfo(
                    " Web", formattedUrl, method, urlString, requestHeaders,
                    responseCode, responseMessage, responseHeaders, responseBody, contentType,
                    stack, null, null
                )
                return checkShouldBlockRequest(info)
            }
        } catch (e: Exception) {
            XposedBridge.log("$LOG_PREFIX Web request error: ${e.message}")
        }
        return false
    }

    private fun createEmptyWebResourceResponse(): Any? {
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
            val classLoader = applicationContext.classLoader
            cronetHttpURLConnectionCls = XposedHelpers.findClass("com.ttnet.org.chromium.net.urlconnection.CronetHttpURLConnection", classLoader)
            urlResponseInfoCls = XposedHelpers.findClass("com.ttnet.org.chromium.net.UrlResponseInfo", classLoader)
            urlRequestCls = XposedHelpers.findClass("com.ttnet.org.chromium.net.UrlRequest", classLoader)
            cronetInputStreamCls = XposedHelpers.findClass("com.ttnet.org.chromium.net.urlconnection.CronetInputStream", classLoader)

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
                    var contentType: String? = null

                    if (hookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false) && httpStatusCode in 200..399) {
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
                                contentType = it
                            }
                        }
                    }

                    val stackTrace = HookUtil.getFormattedStackTrace()
                    val formattedUrl = finalUrl?.let { formatUrlWithoutQuery(Uri.parse(it)) }

                    val info = RequestInfo(
                        " CRONET/$negotiatedProtocol",
                        formattedUrl ?: "",
                        method,
                        finalUrl,
                        requestHeaders,
                        httpStatusCode,
                        httpStatusText,
                        responseHeaders,
                        responseBody,
                        contentType,
                        stackTrace,
                        null,
                        null
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

    private fun sendBlockedRequestBroadcast(
        type: String, info: RequestInfo, isBlocked: Boolean,
        ruleUrl: String?, blockRuleType: String?
    ) {
        if (info.dnsHost?.isNotBlank() == true) {
            if (dnsHostCache.putIfAbsent(info.dnsHost, true) != null) return
        } else if (info.urlString?.isNotBlank() == true) {
            if (urlStringCache.putIfAbsent(info.urlString, true) != null) return
        } else {
            Log.d(LOG_PREFIX, "No DNS or URL.")
            return
        }

        val intent = Intent("com.rikkati.REQUEST")
        try {
            val appName = "${applicationContext.applicationInfo.loadLabel(applicationContext.packageManager)}${info.requestType}"
            val packageName = applicationContext.packageName
            var responseBodyUriString: String? = null
            var responseBodyContentType: String? = null

            info.responseBody?.let { body ->
                try {
                    val encryptedResponseBody = EncryptionUtil.encrypt(body)
                    val values = ContentValues().apply {
                        put("body_content", encryptedResponseBody)
                        put("mime_type", info.responseBodyContentType)
                    }
                    val uri = applicationContext.contentResolver.insert(RESPONSE_BODY_CONTENT_URI, values)
                    if (uri != null) {
                        responseBodyUriString = uri.toString()
                        responseBodyContentType = info.responseBodyContentType
                    } else {
                        Log.e(LOG_PREFIX, "ContentProvider insert failed.")
                    }
                } catch (e: Exception) {
                    Log.e(LOG_PREFIX, "Encryption error: ${e.message}", e)
                }
            }

            val blockedRequest = BlockedRequest(
                appName, packageName, info.requestValue, System.currentTimeMillis(),
                type, isBlocked, ruleUrl, blockRuleType, info.method, info.urlString,
                info.requestHeaders, info.responseCode, info.responseMessage,
                info.responseHeaders, responseBodyUriString, responseBodyContentType,
                info.stack, info.dnsHost, info.fullAddress
            )

            intent.putExtra("request", blockedRequest)
            applicationContext.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(LOG_PREFIX, "Broadcast send error.", e)
        }
    }
}
