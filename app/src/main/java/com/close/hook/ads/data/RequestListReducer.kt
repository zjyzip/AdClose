package com.close.hook.ads.data

import com.close.hook.ads.data.model.RequestInfo
import java.util.Locale

/** Separate transaction updates from repeated DNS observations; never merge unrelated HTTP bodies. */
internal object RequestListReducer {
    fun isDns(item: RequestInfo): Boolean = item.captureType?.trim()?.equals("DNS", true)
        ?: (item.method == null && item.urlString.isNullOrEmpty() && item.dnsHost != null &&
            item.appName.trimEnd().endsWith(" DNS", true))

    fun append(current: List<RequestInfo>, incoming: RequestInfo, limit: Int = 1000): List<RequestInfo> {
        require(limit > 0)
        val item = incoming.copy(occurrenceCount = incoming.occurrenceCount.coerceAtLeast(1L),
            lastTimestamp = maxOf(incoming.timestamp, incoming.lastTimestamp))
        val transaction = if (item.requestId.isEmpty()) -1 else current.indexOfFirst {
            it.packageName == item.packageName && (it.requestId == item.requestId || it.lastRequestId == item.requestId ||
                item.requestId in it.occurrenceSources)
        }
        if (transaction >= 0) {
            val previous = current[transaction]
            val (count, sources) = mergeCounts(previous, item)
            val bodyOnlyUpdate = item.responseCode < 0 && previous.responseCode >= 0
            val updated = item.copy(
                responseCode = if (bodyOnlyUpdate) previous.responseCode else item.responseCode,
                requestType = if (bodyOnlyUpdate) previous.requestType else item.requestType,
                isBlocked = if (bodyOnlyUpdate) previous.isBlocked else item.isBlocked,
                url = item.url ?: previous.url,
                blockType = item.blockType ?: previous.blockType,
                timestamp = previous.timestamp,
                requestId = previous.requestId,
                lastTimestamp = maxOf(previous.lastTimestamp, item.lastTimestamp),
                occurrenceCount = count,
                occurrenceSources = sources,
                requestHeaders = item.requestHeaders ?: previous.requestHeaders,
                responseHeaders = item.responseHeaders ?: previous.responseHeaders,
                responseMessage = item.responseMessage ?: previous.responseMessage,
                responseBodyContentType = item.responseBodyContentType ?: previous.responseBodyContentType,
                requestBodyUriString = item.requestBodyUriString ?: previous.requestBodyUriString,
                responseBodyUriString = item.responseBodyUriString ?: previous.responseBodyUriString
            )
            return current.toMutableList().also { it[transaction] = updated }
        }
        val aggregate = aggregateKey(item)
        if (aggregate != null) {
            val index = current.indexOfFirst { aggregateKey(it) == aggregate }
            if (index >= 0) {
                val previous = current[index]
                val (count, sources) = mergeCounts(previous, item)
                val merged = item.copy(
                    // The row identity is stable even when the latest observation has another transaction id.
                    requestId = previous.requestId,
                    timestamp = previous.timestamp,
                    lastTimestamp = maxOf(previous.lastTimestamp, item.lastTimestamp),
                    occurrenceCount = count,
                    occurrenceSources = sources                )
                // Keep its position: a hot DNS name updates its count, not the whole list's scroll position.
                return current.toMutableList().also { it[index] = merged }
            }
        }
        return buildList(minOf(limit, current.size + 1)) {
            add(item.copy(occurrenceSources = item.occurrenceSources.ifEmpty {
                if (item.requestId.isEmpty()) emptyMap() else mapOf(item.requestId to item.occurrenceCount)
            }))
            addAll(current.take(limit - 1))
        }
    }

    private fun mergeCounts(previous: RequestInfo, item: RequestInfo): Pair<Long, Map<String, Long>> {
        val sources = LinkedHashMap(previous.occurrenceSources.ifEmpty {
            if (previous.requestId.isEmpty()) emptyMap() else mapOf(previous.requestId to previous.occurrenceCount)
        })
        var count = previous.occurrenceCount
        val updates = item.occurrenceSources.ifEmpty {
            if (item.requestId.isEmpty()) emptyMap() else mapOf(item.requestId to item.occurrenceCount)
        }
        for ((id, total) in updates) {
            val old = sources[id] ?: 0L
            val delta = (total - old).coerceAtLeast(0)
            count = if (Long.MAX_VALUE - count < delta) Long.MAX_VALUE else count + delta
            sources.remove(id)
            sources[id] = maxOf(old, total)
        }
        while (sources.size > 64) sources.remove(sources.keys.first())
        return count to sources
    }

    private data class AggregateKey(val capture: String, val packageName: String, val target: String,
        val blocked: Boolean?, val ruleType: String?, val rule: String?, val failure: String?)

    private fun aggregateKey(item: RequestInfo): AggregateKey? {
        val capture = if (isDns(item)) "DNS" else item.captureType?.trim()?.uppercase(Locale.ROOT)
            ?: if (item.appName.trimEnd().endsWith(" CONNECT", true)) "CONNECT" else "HTTP"
        if (capture != "DNS" && capture != "CONNECT" && (item.isBlocked != true || item.responseCode >= 0 ||
            item.requestBodyUriString != null || item.responseBodyUriString != null)) return null
        val target = when (capture) {
            "DNS" -> (item.dnsHost ?: item.request).trim().trimEnd('.').lowercase(Locale.ROOT)
            "CONNECT" -> item.fullAddress ?: item.request
            else -> "${item.method}:${item.request}"
        }
        return AggregateKey(capture, item.packageName, target, item.isBlocked, item.blockType, item.url,
            item.responseMessage.takeIf { item.isBlocked != true && item.fullAddress.isNullOrBlank() })
    }
}
