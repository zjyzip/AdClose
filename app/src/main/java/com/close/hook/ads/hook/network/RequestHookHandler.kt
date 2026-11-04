package com.close.hook.ads.hook.network

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.hook.util.HookUtil
import com.google.common.cache.CacheBuilder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayInputStream
import java.io.FileDescriptor
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ProtocolException
import java.net.UnknownHostException
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLSocket

internal object RequestHookHandler {
    private const val LOG_PREFIX = "[RequestHookHandler]"
    private const val TLS_FRAME = "adclose.tls.frame"
    private const val TLS_DEPTH = "adclose.tls.depth"
    private const val DNS_DEPTH = "adclose.dns.depth"
    private const val READ_DEPTH = "adclose.cronet.readDepth"
    private const val WEB_DEPTH = "adclose.web.depth"
    private const val NATIVE_SCOPE = "adclose.native.scope"
    private val initialized = AtomicBoolean(false)
    private lateinit var applicationContext: Context
    private val hookedClients = ConcurrentHashMap.newKeySet<Class<*>>()
    private val readDepth = ThreadLocal.withInitial { 0 }
    private val tlsStreamDepth = ThreadLocal.withInitial { 0 }
    private val webDepth = ThreadLocal.withInitial { 0 }
    private val dnsDepth = ThreadLocal.withInitial { 0 }
    private val engineDepth = ThreadLocal.withInitial { IdentityHashMap<Any, IntArray>() }
    private data class TlsObservation(val id: Long, val lock: Any = Any())
    private val tlsConnections = CacheBuilder.newBuilder().weakKeys().maximumSize(2048)
        .expireAfterAccess(10, TimeUnit.MINUTES).build<Any, TlsObservation>()
    private val tlsId = AtomicLong(1L shl 48)
    private val cronetIds = CacheBuilder.newBuilder().weakKeys().maximumSize(512)
        .expireAfterAccess(3, TimeUnit.MINUTES).build<Any, String>()
    private val captures = CacheBuilder.newBuilder().maximumWeight(16L * 1024 * 1024)
        .weigher<Any, PassiveBodyCapture> { _, capture -> capture.weight }
        .expireAfterAccess(3, TimeUnit.MINUTES)
        .removalListener<Any, PassiveBodyCapture> { entry -> if (entry.wasEvicted()) entry.value?.cancel() }
        .build<Any, PassiveBodyCapture>()

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        applicationContext = context.applicationContext ?: context
        // Parser-only JNI initialization installs no libc/SSL hooks. Java capture is independent of switch_nine.
        NativeRequestHook.init(false)
        listOf("DNS" to ::setupDNSRequestHook, "connect" to ::setupConnectHook,
            "socket" to ::setupSocketHook, "Conscrypt" to ::setupConscryptEngineHook,
            "Conscrypt sockets" to ::setupConscryptSocketHooks, "OkHttp requests" to ::setupHttp2RequestHook,
            "WebView" to ::setupWebViewRequestHook, "Cronet" to ::setupCronetRequestHook).forEach { (name, setup) ->
            try { setup() } catch (error: Throwable) { XposedBridge.log("$LOG_PREFIX $name setup failed: $error") }
        }
    }

    private fun setupConnectHook() {
        HookUtil.findAndHookMethod("libcore.io.BlockGuardOs", "connect",
            arrayOf(FileDescriptor::class.java, InetAddress::class.java, Int::class.javaPrimitiveType), "before") { param ->
            val address = param.args[1] as? InetAddress ?: return@findAndHookMethod
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) return@findAndHookMethod
            val ip = address.hostAddress ?: return@findAndHookMethod
            val host = RequestHook.getUnambiguousDnsHost(ip)
            val info = BlockedRequest(" CONNECT", host ?: ip, null, null, null, null, -1, null,
                null, null, null, HookUtil.getFormattedStackTrace(), host, "$ip:${param.args[2]}", UUID.randomUUID().toString())
            if (RequestHook.checkShouldBlockRequest(info)) param.throwable = ConnectException("Blocked by AdClose: ${host ?: ip}")
        }
    }

    private fun setupDNSRequestHook() {
        for (name in listOf("getByName", "getAllByName")) {
            hookDeclaredCallbacks(InetAddress::class.java, name, before = { param ->
                val depth = dnsDepth.get() ?: 0
                param.setObjectExtra(DNS_DEPTH, depth)
                dnsDepth.set(depth + 1)
            }, after = dnsAfter@ { param ->
                val depth = param.getObjectExtra(DNS_DEPTH) as? Int ?: return@dnsAfter
                dnsDepth.set(depth)
                // getByName commonly delegates to getAllByName: count one external lookup, not both hooks.
                if (depth != 0) return@dnsAfter
                if (RequestHook.processDnsRequest(param.args.firstOrNull(), param.result, param.throwable)) {
                    param.throwable = UnknownHostException("Blocked by AdClose: ${param.args.firstOrNull()}")
                }
            })
        }
    }

    private fun setupSocketHook() {
        hookDeclaredCallbacks(Class.forName("java.net.SocketOutputStream"), "socketWrite0", before = socketWrite@ { param ->
            val socket = XposedHelpers.getObjectField(param.thisObject, "socket") ?: return@socketWrite
            if (socket is SSLSocket) return@socketWrite
            val bytes = param.args[1] as? ByteArray ?: return@socketWrite
            val offset = param.args[2] as? Int ?: return@socketWrite
            val length = param.args[3] as? Int ?: return@socketWrite
            if (offset < 0 || length <= 0 || offset > bytes.size - length) return@socketWrite
            if (RequestHook.processRequestData(socket, bytes, offset, length, false)) {
                param.throwable = IOException("Request blocked by AdClose")
            }
        }, after = {}, javaScope = true)
        hookDeclaredCallbacks(Class.forName("java.net.SocketInputStream"), "socketRead0", before = {}, after = socketRead@ { param ->
            if (param.throwable != null) return@socketRead
            val socket = XposedHelpers.getObjectField(param.thisObject, "socket") ?: return@socketRead
            if (socket is SSLSocket) return@socketRead
            val count = param.result as? Int ?: return@socketRead
            val blocked = if (count == -1) RequestHook.closeConnection(socket) else {
                val bytes = param.args[1] as? ByteArray ?: return@socketRead
                val offset = param.args[2] as? Int ?: return@socketRead
                if (count <= 0 || offset < 0 || offset > bytes.size - count) return@socketRead
                RequestHook.processResponseData(socket, bytes, offset, count)
            }
            if (blocked) param.throwable = IOException("Request blocked by AdClose")
        }, javaScope = true)
        HookUtil.hookAllMethods("java.net.Socket", "close", "after") { param -> RequestHook.closeConnection(param.thisObject) }
    }

    private fun observation(connection: Any): TlsObservation =
        tlsConnections.get(connection) { TlsObservation(tlsId.incrementAndGet()) }

    private data class EngineFrame(val depths: IntArray, val direction: Int, val depth: Int,
        val buffers: NetworkBuffers.Snapshot?)

    private fun setupConscryptEngineHook() {
        val clazz = Class.forName("com.android.org.conscrypt.ConscryptEngine")
        for (name in listOf("wrap", "unwrap")) {
            val write = name == "wrap"
            val direction = if (write) 0 else 1
            hookDeclaredCallbacks(clazz, name, before = { param ->
                val depths = checkNotNull(engineDepth.get()).getOrPut(param.thisObject) { IntArray(2) }
                val depth = depths[direction]++
                param.setObjectExtra(TLS_FRAME, EngineFrame(depths, direction, depth,
                    if (depth == 0) NetworkBuffers.snapshot(param.args, write) else null))
            }, after = engineAfter@ { param ->
                val frame = param.getObjectExtra(TLS_FRAME) as? EngineFrame ?: return@engineAfter
                frame.depths[frame.direction] = frame.depth
                if (frame.depths.all { it == 0 }) engineDepth.get()?.remove(param.thisObject)
                if (frame.depth != 0 || param.throwable != null) return@engineAfter
                val result = param.result as? SSLEngineResult ?: return@engineAfter
                val count = if (write) result.bytesConsumed() else result.bytesProduced()
                if (count <= 0) return@engineAfter
                val bytes = frame.buffers?.copyAdvanced(count) ?: return@engineAfter
                if (processTlsData(param.thisObject, write, bytes)) {
                    param.throwable = ProtocolException("Request blocked by AdClose")
                }
            }, javaScope = true)
        }
        for (name in listOf("closeInbound", "closeOutbound")) {
            HookUtil.hookAllMethods(clazz, name, "after") { param ->
                // Closing just the outbound half still permits reading the pending response.
                val inboundDone = XposedHelpers.callMethod(param.thisObject, "isInboundDone") == true
                val outboundDone = XposedHelpers.callMethod(param.thisObject, "isOutboundDone") == true
                if (inboundDone && outboundDone) closeTlsConnection(param.thisObject)
            }
        }
    }

    private fun processTlsData(connection: Any, write: Boolean, bytes: ByteArray): Boolean {
        val state = observation(connection)
        return synchronized(state.lock) {
            val h2 = NativeRequestHook.feedH2DataIfAvailable(state.id, write, bytes,
                NativeRequestHook.getCollectResponseBody())
            h2 == 0 && if (write) RequestHook.processRequestData(connection, bytes, isHttps = true)
            else RequestHook.processResponseData(connection, bytes)
        }
    }

    private fun closeTlsConnection(connection: Any) {
        tlsConnections.getIfPresent(connection)?.id?.let(NativeRequestHook::closeJavaConnection)
        tlsConnections.invalidate(connection)
        RequestHook.closeConnection(connection)
    }

    private fun setupConscryptSocketHooks() {
        // EngineSocket already passes through the engine hooks above. These sockets use native SSL directly.
        for (name in listOf("ConscryptFileDescriptorSocket", "OpenSSLSocketImpl")) {
            val socketClass = XposedHelpers.findClassIfExists("com.android.org.conscrypt.$name", null) ?: continue
            socketClass.declaredClasses.forEach { streamClass ->
                for (method in listOf("read", "write")) {
                    val write = method == "write"
                    hookDeclaredCallbacks(streamClass, method, before = socketBefore@ { param ->
                        val depth = tlsStreamDepth.get() ?: 0
                        param.setObjectExtra(TLS_DEPTH, depth)
                        tlsStreamDepth.set(depth + 1)
                        if (depth != 0 || !write) return@socketBefore
                        val bytes = when (val value = param.args.firstOrNull()) {
                            is ByteArray -> value
                            is Int -> byteArrayOf(value.toByte())
                            else -> return@socketBefore
                        }
                        val offset = param.args.getOrNull(1) as? Int ?: 0
                        val length = param.args.getOrNull(2) as? Int ?: bytes.size
                        if (offset < 0 || length <= 0 || offset > bytes.size - length) return@socketBefore
                        val socket = field(param.thisObject, "this\$0") ?: return@socketBefore
                        if (processTlsData(socket, true, bytes.copyOfRange(offset, offset + length))) {
                            param.throwable = IOException("Request blocked by AdClose")
                        }
                    }, after = socketAfter@ { param ->
                        val depth = param.getObjectExtra(TLS_DEPTH) as? Int ?: return@socketAfter
                        tlsStreamDepth.set(depth)
                        if (depth != 0 || write || param.throwable != null) return@socketAfter
                        val socket = field(param.thisObject, "this\$0") ?: return@socketAfter
                        val count = param.result as? Int ?: return@socketAfter
                        if (count == -1) {
                            RequestHook.closeConnection(socket)
                            return@socketAfter
                        }
                        val bytes = if (param.args.isEmpty() && count >= 0) byteArrayOf(count.toByte()) else {
                            val buffer = param.args.firstOrNull() as? ByteArray ?: return@socketAfter
                            val offset = param.args.getOrNull(1) as? Int ?: 0
                            if (count <= 0 || offset < 0 || offset > buffer.size - count) return@socketAfter
                            buffer.copyOfRange(offset, offset + count)
                        }
                        if (processTlsData(socket, false, bytes)) param.throwable = IOException("Request blocked by AdClose")
                    }, javaScope = true)
                }
            }
            HookUtil.hookAllMethods(socketClass, "close", "after") { param -> closeTlsConnection(param.thisObject) }
        }
    }

    private fun setupHttp2RequestHook() {
        for (name in listOf("okhttp3.internal.http2.Http2Connection",
            "com.android.okhttp.internal.framed.FramedConnection",
            "com.squareup.okhttp.internal.framed.FramedConnection")) {
            val clazz = XposedHelpers.findClassIfExists(name, applicationContext.classLoader) ?: continue
            hookDeclaredCallbacks(clazz, "newStream", before = streamBefore@ { param ->
                val raw = param.args.filterIsInstance<List<*>>().firstOrNull() ?: return@streamBefore
                val headers = raw.mapNotNull { header ->
                    header ?: return@mapNotNull null
                    fun text(value: Any?): String? = if (value is String) value else value?.let {
                        runCatching { XposedHelpers.callMethod(it, "utf8") as? String }.getOrNull()
                    }
                    val key = text(field(header, "name")) ?: return@mapNotNull null
                    val value = text(field(header, "value")) ?: return@mapNotNull null
                    key to value
                }
                val url = NetworkHeaders.http2Url(headers) ?: return@streamBefore
                val method = headers.firstOrNull { it.first == ":method" }?.second ?: return@streamBefore
                val info = BlockedRequest(" H2", NetworkUrl.withoutQuery(url), method, url,
                    NetworkHeaders.format(headers), null, -1, null, null, null, null,
                    HookUtil.getFormattedStackTrace(), null, null, UUID.randomUUID().toString())
                if (RequestHook.checkShouldBlockRequest(info, reportAllowed = false)) {
                    param.throwable = IOException("Request blocked by AdClose")
                }
            }, after = {})
        }
    }

    private fun setupWebViewRequestHook() {
        HookUtil.findAndHookMethod(WebView::class.java, "setWebViewClient", arrayOf(WebViewClient::class.java), "before") { param ->
            val client = param.args[0] as? WebViewClient ?: return@findAndHookMethod
            var clazz: Class<*>? = client.javaClass
            while (clazz != null && WebViewClient::class.java.isAssignableFrom(clazz)) {
                if (hookedClients.add(clazz)) hookClientMethods(clazz)
                clazz = clazz.superclass
            }
        }
    }

    private fun hookDeclaredCallbacks(
        clazz: Class<*>, name: String,
        before: (XC_MethodHook.MethodHookParam) -> Unit,
        after: (XC_MethodHook.MethodHookParam) -> Unit,
        javaScope: Boolean = false
    ) {
        clazz.declaredMethods.filter { it.name == name && !Modifier.isAbstract(it.modifiers) }.forEach { method ->
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (javaScope) param.setObjectExtra(NATIVE_SCOPE, NativeRequestHook.beginJavaCapture())
                        try { before(param) }
                        catch (error: Throwable) {
                            if (param.getObjectExtra(NATIVE_SCOPE) == true) {
                                NativeRequestHook.endJavaCapture()
                                param.setObjectExtra(NATIVE_SCOPE, false)
                            }
                            throw error
                        }
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try { after(param) }
                        finally {
                            if (param.getObjectExtra(NATIVE_SCOPE) == true) NativeRequestHook.endJavaCapture()
                        }
                    }
                })
            } catch (error: Throwable) { XposedBridge.log("$LOG_PREFIX Cannot hook $method: $error") }
        }
    }

    private fun hookClientMethods(clazz: Class<*>) {
        for (name in listOf("shouldInterceptRequest", "shouldOverrideUrlLoading")) {
            hookDeclaredCallbacks(clazz, name, before = webBefore@ { param ->
                val depth = webDepth.get() ?: 0
                param.setObjectExtra(WEB_DEPTH, depth)
                webDepth.set(depth + 1)
                if (depth != 0 || param.args.size != 2 || !processWebRequest(param.args[1])) return@webBefore
                if (name == "shouldOverrideUrlLoading") param.result = true
                else param.result = WebResourceResponse("text/plain", "UTF-8", 204, "No Content",
                    emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }, after = { param ->
                (param.getObjectExtra(WEB_DEPTH) as? Int)?.let(webDepth::set)
            })
        }
    }

    private fun processWebRequest(request: Any?): Boolean {
        val web = request as? WebResourceRequest
        val url = web?.url?.toString() ?: request as? String ?: return false
        return RequestHook.checkShouldBlockRequest(BlockedRequest(" Web", NetworkUrl.withoutQuery(url),
            web?.method ?: "GET", url, web?.requestHeaders?.toString(), null, -1, null, null, null,
            null, HookUtil.getFormattedStackTrace(), null, null, UUID.randomUUID().toString()))
    }

    private fun field(instance: Any, name: String): Any? = runCatching { XposedHelpers.getObjectField(instance, name) }.getOrNull()

    private fun setupCronetRequestHook() {
        val loader = applicationContext.classLoader
        val streamClass = XposedHelpers.findClassIfExists("com.ttnet.org.chromium.net.urlconnection.CronetInputStream", loader)
        val connectionClass = XposedHelpers.findClassIfExists("com.ttnet.org.chromium.net.urlconnection.CronetHttpURLConnection", loader)
            ?: return
        for (name in listOf("connect", "getResponse")) {
            HookUtil.hookAllMethods(connectionClass, name, "before") { param ->
                val connection = param.thisObject as? HttpURLConnection ?: return@hookAllMethods
                val url = connection.url?.toString() ?: return@hookAllMethods
                val request = field(connection, "mRequest")
                val info = BlockedRequest(" CRONET", NetworkUrl.withoutQuery(url), connection.requestMethod,
                    url, request?.let { field(it, "mRequestHeaders") }?.toString(), null, -1, null, null,
                    null, null, HookUtil.getFormattedStackTrace(), null, null,
                    cronetIds.get(connection) { UUID.randomUUID().toString() })
                if (RequestHook.checkShouldBlockRequest(info)) param.throwable = IOException("Request blocked by AdClose (Cronet)")
            }
        }
        HookUtil.hookAllMethods(connectionClass, "getResponse", "after") { param ->
            if (param.throwable != null) return@hookAllMethods
            val connection = param.thisObject ?: return@hookAllMethods
            val request = field(connection, "mRequest") ?: return@hookAllMethods
            val response = field(connection, "mResponseInfo") ?: return@hookAllMethods
            val url = XposedHelpers.callMethod(response, "getUrl") as? String
                ?: field(request, "mInitialUrl") as? String ?: return@hookAllMethods
            val code = XposedHelpers.callMethod(response, "getHttpStatusCode") as? Int ?: -1
            val rawHeaders = XposedHelpers.callMethod(response, "getAllHeaders") as? Map<*, *>
            val headers = rawHeaders?.entries?.mapNotNull { (key, value) ->
                val name = key as? String ?: return@mapNotNull null
                name to (value as? List<*>)?.filterIsInstance<String>().orEmpty()
            }?.toMap().orEmpty()
            fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
            val info = BlockedRequest(" CRONET", NetworkUrl.withoutQuery(url), field(request, "mInitialMethod") as? String,
                url, field(request, "mRequestHeaders")?.toString(), null, code,
                XposedHelpers.callMethod(response, "getHttpStatusText") as? String,
                headers.entries.joinToString("\n") { (name, values) -> "$name: ${values.joinToString(", ")}" },
                null, header("content-type"), HookUtil.getFormattedStackTrace(), null, null,
                cronetIds.get(connection) { UUID.randomUUID().toString() })
            if (RequestHook.checkShouldBlockRequest(info)) {
                param.throwable = IOException("Request blocked by AdClose (Cronet)")
                return@hookAllMethods
            }
            val stream = field(connection, "mInputStream") ?: return@hookAllMethods
            if (streamClass == null || !streamClass.isInstance(stream) || !NativeRequestHook.getCollectResponseBody()) return@hookAllMethods
            if (captures.getIfPresent(stream) != null) return@hookAllMethods
            val length = if (header("content-encoding") == null) header("content-length")?.toLongOrNull() else null
            captures.put(stream, PassiveBodyCapture(5 * 1024 * 1024, length) { bytes ->
                if (bytes != null) RequestHook.reportAllowedResponse(info.copy(responseBody = bytes))
            })
        }
        if (streamClass == null) return
        hookDeclaredCallbacks(streamClass, "read", before = readBefore@ { param ->
            if (captures.getIfPresent(param.thisObject) == null) return@readBefore
            val depth = readDepth.get() ?: 0
            param.setObjectExtra(READ_DEPTH, depth)
            readDepth.set(depth + 1)
        }, after = readAfter@ { param ->
            val depth = param.getObjectExtra(READ_DEPTH) as? Int ?: return@readAfter
            readDepth.set(depth)
            if (depth != 0 || param.throwable != null) return@readAfter
            val capture = captures.getIfPresent(param.thisObject) ?: return@readAfter
            val count = param.result as? Int ?: return@readAfter
            if (count == -1) capture.finish()
            else if (param.args.isEmpty() && count >= 0) capture.append(byteArrayOf(count.toByte()), 0, 1)
            else if (count > 0) {
                val bytes = param.args[0] as? ByteArray ?: return@readAfter
                val offset = param.args.getOrNull(1) as? Int ?: 0
                if (offset >= 0 && count <= bytes.size - offset) capture.append(bytes, offset, count)
            }
            if (capture.isDone) captures.invalidate(param.thisObject) else captures.put(param.thisObject, capture)
        })
        HookUtil.hookAllMethods(streamClass, "close", "after") { param ->
            captures.asMap().remove(param.thisObject)?.cancel()
        }
    }
}
