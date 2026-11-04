package com.close.hook.ads.hook.network

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.util.Log
import com.close.hook.ads.data.model.BlockedRequest
import com.close.hook.ads.data.model.RequestInfo
import com.close.hook.ads.data.repository.RuleRepository
import com.close.hook.ads.hook.util.HookUtil
import com.close.hook.ads.preference.HookPrefs
import com.close.hook.ads.provider.TemporaryFileProvider
import com.close.hook.ads.util.BodyUploadStore
import com.close.hook.ads.util.AppUtils
import com.google.common.cache.CacheBuilder
import java.net.InetAddress
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object RequestHook {
    private const val LOG_PREFIX = "[RequestHook]"
    const val REQUEST_ACTION = "com.rikkati.REQUEST"
    const val REQUEST_BATCH = "requests"
    private const val MAX_BATCH_BYTES = 192 * 1024
    internal lateinit var applicationContext: Context
    private val notifications = RequestBroadcastBuffer()
    private val broadcaster = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "AdClose-AsyncBroadcast").apply { isDaemon = true }
    }.apply {
        scheduleWithFixedDelay({
            try { NetworkObservationScope.run { flushNotifications() } }
            catch (error: Throwable) { Log.w(LOG_PREFIX, "Request notification flush failed", error) }
        }, 200, 200, TimeUnit.MILLISECONDS)
    }
    private val cachedAppLabel by lazy {
        applicationContext.applicationInfo.loadLabel(applicationContext.packageManager).toString()
    }
    private data class StoredBodies(val request: String? = null, val response: String? = null)
    private val storedBodies = CacheBuilder.newBuilder().maximumSize(5000)
        .expireAfterAccess(10, TimeUnit.MINUTES).build<String, StoredBodies>()
    internal val dnsReverseCache = CacheBuilder.newBuilder().maximumSize(4000)
        .expireAfterWrite(15, TimeUnit.MINUTES).build<String, MutableSet<String>>().asMap()
    private val sessions = CacheBuilder.newBuilder().maximumWeight(32L * 1024 * 1024)
        .weigher<Any, Session> { _, session -> session.cachedWeight }
        .expireAfterAccess(3, TimeUnit.MINUTES).build<Any, Session>()

    private class Session(val https: Boolean) {
        @Volatile var cachedWeight = 128 * 1024
        var closed = false
        var source: String? = null
        var address: String? = null
        var nativeStack: String? = null
        val requests = LinkedHashMap<String, BlockedRequest>()
        val parser = Http1Connection(https,
            collectBody = { HookPrefs.getBoolean(HookPrefs.KEY_COLLECT_RESPONSE_BODY, false) },
            onRequest = { request ->
                val value = request.url?.let(NetworkUrl::withoutQuery) ?: request.target
                val info = BlockedRequest(source ?: if (https) " HTTPS" else " HTTP", value,
                    request.method, request.url, request.headers.substringAfter("\r\n", ""), null,
                    -1, null, null, null, null, captureStack(nativeStack), null, address, request.id)
                requests[request.id] = info
                checkShouldBlockRequest(info)
            },
            onResponse = { response ->
                responseInfo(response)?.let(::reportAllowedResponse)
                false
            },
            onRequestBody = { request ->
                requests[request.id]?.copy(requestBody = request.body)?.let { info ->
                    requests[request.id] = info
                    reportAllowedResponse(info)
                }
            },
            onResponseHeaders = { response -> responseInfo(response)?.let(::reportAllowedResponse) })

        private fun responseInfo(response: Http1Connection.Response): BlockedRequest? {
            val initial = requests[response.request.id] ?: return null
            val headers = Http1Connection.parseHeaders(response.headers)
            val encoding = headers["content-encoding"]?.firstOrNull()
            val contentType = headers["content-type"]?.firstOrNull() ?: "application/octet-stream"
            return initial.copy(
                requestBody = response.request.body,
                responseCode = response.code,
                responseMessage = response.headers.substringBefore("\r\n").split(' ', limit = 3).getOrNull(2),
                responseHeaders = response.headers.substringAfter("\r\n", ""),
                responseBody = response.body,
                responseBodyContentType = if (encoding.isNullOrBlank()) contentType else "$contentType; encoding=$encoding")
        }

        fun trimMetadata() { requests.keys.retainAll(parser.pendingRequestIds) }
        fun weight(): Int = parser.cacheWeightBytes + requests.values.sumOf {
            ((it.stack?.length ?: 0) + (it.requestHeaders?.length ?: 0)) * 2
        }
    }

    fun init(context: Context) {
        applicationContext = context.applicationContext ?: context
        RuleRepository.init(applicationContext)
    }

    internal fun formatUrlWithoutQuery(urlObject: Any?): String =
        urlObject?.toString()?.let(NetworkUrl::withoutQuery).orEmpty()

    internal fun processRequestData(
        key: Any, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset,
        isHttps: Boolean, source: String? = null, address: String? = null, nativeStack: String? = null
    ): Boolean {
        if (NetworkObservationScope.active) return false
        val session = sessions.get(key) { Session(isHttps) }
        return synchronized(session) {
            if (session.closed) return@synchronized false
            session.source = source
            session.address = address
            session.nativeStack = nativeStack
            try {
                val blocked = session.parser.requestBytes(bytes, offset, length)
                session.trimMetadata()
                session.cachedWeight = session.weight()
                sessions.put(key, session)
                blocked
            } catch (error: Exception) {
                session.closed = true
                sessions.asMap().remove(key, session)
                Log.w(LOG_PREFIX, "Request observation failed", error)
                false
            }
        }
    }

    internal fun processResponseData(key: Any, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Boolean {
        if (NetworkObservationScope.active) return false
        val session = sessions.getIfPresent(key) ?: return false
        return synchronized(session) {
            if (session.closed) return@synchronized false
            try {
                val blocked = session.parser.responseBytes(bytes, offset, length)
                session.trimMetadata()
                session.cachedWeight = session.weight()
                sessions.put(key, session)
                blocked
            } catch (error: Exception) {
                session.closed = true
                sessions.asMap().remove(key, session)
                Log.w(LOG_PREFIX, "Response observation failed", error)
                false
            }
        }
    }

    internal fun discardConnection(key: Any) {
        val session = sessions.asMap().remove(key) ?: return
        synchronized(session) {
            session.closed = true
            sessions.asMap().remove(key, session)
            session.requests.clear()
        }
    }

    internal fun closeConnection(key: Any): Boolean {
        val session = sessions.asMap().remove(key) ?: return false
        return synchronized(session) {
            if (session.closed) return@synchronized false
            session.closed = true
            sessions.asMap().remove(key, session)
            try { session.parser.endOfInput() }
            catch (error: Exception) { Log.w(LOG_PREFIX, "Connection cleanup failed", error); false }
            finally { session.requests.clear() }
        }
    }

    internal fun checkShouldBlockRequest(info: BlockedRequest?, reportAllowed: Boolean = true): Boolean {
        if (info == null || NetworkObservationScope.active) return false
        val match = if (info.requestValue.isBlank()) null else RuleRepository.shouldBlock(
            info.requestValue, AppUtils.extractHostOrSelf(info.requestValue))
        val blocked = match?.matched == true
        if (blocked || reportAllowed) sendBlockedRequestBroadcast(info, blocked, match?.ruleUrl, match?.ruleType)
        return blocked
    }

    internal fun reportAllowedResponse(info: BlockedRequest) {
        if (NetworkObservationScope.active) return
        // A passive tap reports bytes already delivered; it must not label them as newly blocked.
        sendBlockedRequestBroadcast(info, false, null, null)
    }

    internal fun processDnsRequest(hostObject: Any?, result: Any?, failure: Throwable? = null): Boolean {
        if (NetworkObservationScope.active) return false
        val host = hostObject as? String ?: return false
        val addresses = when (result) {
            is InetAddress -> listOf(result)
            is Array<*> -> result.filterIsInstance<InetAddress>()
            else -> emptyList()
        }.mapNotNull { it.hostAddress }
        // Failed lookups are still observations, not a reason to hide the request.
        if (host.isBlank()) return false
        addresses.forEach { ip ->
            dnsReverseCache.computeIfAbsent(ip) { ConcurrentHashMap.newKeySet() }.add(host.lowercase(Locale.ROOT))
        }
        return checkShouldBlockRequest(BlockedRequest(" DNS", host, null, null, null, null, -1,
            failure?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }, null, null, null,
            captureStack(null), host, addresses.joinToString(", "), UUID.randomUUID().toString()))
    }

    internal fun getUnambiguousDnsHost(ip: String): String? = dnsReverseCache[ip]?.singleOrNull()

    private fun captureStack(nativeStack: String?): String {
        val javaStack = HookUtil.getFormattedStackTrace()
        return if (nativeStack.isNullOrBlank() || listOf("libjavacrypto.so", "libopenjdk.so", "NET_Send", "NET_Read")
            .any(nativeStack::contains)) javaStack else "$nativeStack\n$javaStack"
    }

    private fun sendBlockedRequestBroadcast(info: BlockedRequest, blocked: Boolean, rule: String?, type: String?) {
        notifications.offer(info, blocked, rule, type, System.currentTimeMillis())
    }

    private fun store(body: ByteArray?, mime: String?): String? {
        val bytes = body ?: return null
        if (bytes.isEmpty()) return null
        return runCatching {
            val resolver = applicationContext.contentResolver
            val begin = Bundle().apply {
                putInt(TemporaryFileProvider.KEY_BODY_SIZE, bytes.size)
                putString(TemporaryFileProvider.KEY_MIME_TYPE, mime ?: "application/octet-stream")
            }
            val token = requireNotNull(resolver.call(TemporaryFileProvider.CONTENT_URI,
                TemporaryFileProvider.METHOD_BEGIN_BODY, null, begin)
                ?.getString(TemporaryFileProvider.KEY_UPLOAD_TOKEN))
            var committed = false
            try {
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(bytes.size, offset + BodyUploadStore.CHUNK_BYTES)
                    val chunk = Bundle().apply {
                        putByteArray(TemporaryFileProvider.KEY_BODY_CONTENT, bytes.copyOfRange(offset, end))
                    }
                    resolver.call(TemporaryFileProvider.CONTENT_URI,
                        TemporaryFileProvider.METHOD_APPEND_BODY, token, chunk)
                    offset = end
                }
                val uri = requireNotNull(resolver.call(TemporaryFileProvider.CONTENT_URI,
                    TemporaryFileProvider.METHOD_FINISH_BODY, token, null)
                    ?.getString(TemporaryFileProvider.KEY_BODY_URI))
                committed = true
                uri
            } finally {
                if (!committed) runCatching { resolver.call(TemporaryFileProvider.CONTENT_URI,
                    TemporaryFileProvider.METHOD_ABORT_BODY, token, null) }
            }
        }.getOrElse { Log.w(LOG_PREFIX, "Cannot store request body", it); null }
    }

    private fun notification(event: RequestBroadcastBuffer.Event): RequestInfo {
        val snapshot = event.info
        val key = snapshot.requestId
        val previous = storedBodies.getIfPresent(key) ?: StoredBodies()
        val requestUri = previous.request ?: store(snapshot.requestBody, "text/plain")
        val responseUri = previous.response ?: store(snapshot.responseBody, snapshot.responseBodyContentType)
        storedBodies.put(key, StoredBodies(requestUri, responseUri))
        return RequestInfo(
            appName = "$cachedAppLabel${snapshot.requestType}", packageName = applicationContext.packageName,
            request = snapshot.requestValue, timestamp = event.timestamp,
            requestType = if (event.blocked) "block" else "pass", isBlocked = event.blocked,
            url = event.rule, blockType = event.ruleType, method = snapshot.method,
            urlString = snapshot.urlString, requestHeaders = snapshot.requestHeaders,
            requestBodyUriString = requestUri, responseCode = snapshot.responseCode,
            responseMessage = snapshot.responseMessage, responseHeaders = snapshot.responseHeaders,
            responseBodyUriString = responseUri, responseBodyContentType = snapshot.responseBodyContentType,
            stack = snapshot.stack, dnsHost = snapshot.dnsHost, fullAddress = snapshot.fullAddress,
            requestId = key, captureType = snapshot.requestType.trim(), occurrenceCount = event.count,
            lastTimestamp = event.lastTimestamp)
    }

    private fun flushNotifications() {
        if (!::applicationContext.isInitialized) return
        val events = notifications.drain(64)
        if (events.isEmpty()) return
        val failed = ArrayList<RequestBroadcastBuffer.Event>()
        val batch = ArrayList<RequestInfo>()
        val batchEvents = ArrayList<RequestBroadcastBuffer.Event>()
        var batchBytes = 0
        fun send() {
            if (batch.isEmpty()) return
            try {
                applicationContext.sendBroadcast(Intent(REQUEST_ACTION).setPackage("com.close.hook.ads")
                    .putParcelableArrayListExtra(REQUEST_BATCH, ArrayList(batch)))
            } catch (error: Throwable) {
                failed += batchEvents
                Log.w(LOG_PREFIX, "Request batch broadcast failed", error)
            } finally {
                batch.clear()
                batchEvents.clear()
                batchBytes = 0
            }
        }
        for (event in events) {
            try {
                var item = notification(event)
                fun parcelSize(value: RequestInfo): Int {
                    val parcel = Parcel.obtain()
                    return try { value.writeToParcel(parcel, 0); parcel.dataSize() }
                    finally { parcel.recycle() }
                }
                var bytes = parcelSize(item)
                if (bytes > MAX_BATCH_BYTES) {
                    item = item.copy(stack = item.stack?.take(8192), requestHeaders = item.requestHeaders?.take(16384),
                        responseHeaders = item.responseHeaders?.take(16384))
                    bytes = parcelSize(item)
                }
                if (batchBytes + bytes > MAX_BATCH_BYTES) send()
                if (bytes <= MAX_BATCH_BYTES) {
                    batch += item
                    batchEvents += event
                    batchBytes += bytes
                }
            } catch (error: Throwable) {
                failed += event
                Log.w(LOG_PREFIX, "Request notification conversion failed", error)
            }
        }
        send()
        if (failed.isNotEmpty()) notifications.retry(failed)
    }
}
