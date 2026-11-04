package com.close.hook.ads.hook.network

import com.close.hook.ads.data.model.BlockedRequest
import java.util.Locale

/** Coalesces notification traffic only; rule decisions always stay on the caller's path. */
internal class RequestBroadcastBuffer(
    private val maxPending: Int = 256,
    private val maxBodyBytes: Long = 16L * 1024 * 1024,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    data class Event(val info: BlockedRequest, val blocked: Boolean, val rule: String?, val ruleType: String?,
        val count: Long, val timestamp: Long, val lastTimestamp: Long, val attempts: Int = 0)
    private data class GroupKey(val capture: String, val target: String, val method: String?,
        val blocked: Boolean, val rule: String?, val ruleType: String?, val failure: String?)
    private data class Seen(val fields: Int, val rowId: String, val group: GroupKey?, val updated: Long)
    private data class Group(val id: String, val count: Long, val timestamp: Long, val updated: Long)
    private val seen = LinkedHashMap<String, Seen>()
    private val groups = LinkedHashMap<GroupKey, Group>()
    private val pending = LinkedHashMap<String, Event>()
    private var bodyBytes = 0L

    @Synchronized fun offer(info: BlockedRequest, blocked: Boolean, rule: String?, ruleType: String?, timestamp: Long) {
        if (info.requestId.isBlank()) return
        val time = now()
        prune(time)
        val old = seen[info.requestId]
        val added = fields(info, blocked)
        if (old != null && (old.fields or added) == old.fields) return
        val capture = info.requestType.trim().uppercase(Locale.ROOT)
        val target = (when (capture) {
            "CONNECT" -> info.fullAddress ?: info.requestValue
            "DNS" -> info.dnsHost ?: info.requestValue
            else -> if (blocked) info.requestValue else info.urlString ?: info.requestValue
        }).let {
            if (capture == "DNS") it.trim().trimEnd('.').lowercase(Locale.ROOT) else it
        }
        val aggregate = (capture == "DNS" || capture == "CONNECT" || blocked) && target.length <= 4096 &&
            info.responseCode < 0 && info.requestBody == null && info.responseBody == null
        val key = old?.group ?: if (aggregate) GroupKey(capture, target, info.method, blocked, rule, ruleType,
            info.responseMessage.takeIf { !blocked && info.fullAddress.isNullOrBlank() }) else null
        val group = key?.let { groups[it] }
        val id = old?.rowId ?: group?.id ?: info.requestId
        val queued = pending[id]
        if (queued == null && pending.size >= maxPending && key == null && !blocked &&
            info.requestBody == null && info.responseBody == null) return
        var snapshot = merge(queued?.info, info.copy(requestId = id))
        val retained = queued?.let { size(it.info) } ?: 0L
        if (size(snapshot) - retained > maxBodyBytes - bodyBytes) {
            // Retain bodies already queued; an out-of-order update must not erase them or exceed the budget.
            snapshot = snapshot.copy(requestBody = queued?.info?.requestBody, responseBody = queued?.info?.responseBody)
        }
        val observed = fields(snapshot, blocked)
        seen.remove(info.requestId)
        seen[info.requestId] = Seen((old?.fields ?: 0) or observed, id, key, time)
        val count = if (old != null) group?.count ?: queued?.count ?: 1L
            else if (group == null) 1L else if (group.count == Long.MAX_VALUE) Long.MAX_VALUE else group.count + 1
        val firstTimestamp = group?.timestamp ?: queued?.timestamp ?: timestamp
        if (key != null) {
            groups.remove(key)
            groups[key] = Group(id, count, firstTimestamp, time)
        }
        bodyBytes -= retained
        pending[id] = Event(snapshot, blocked || queued?.blocked == true,
            rule ?: queued?.rule, ruleType ?: queued?.ruleType, maxOf(count, queued?.count ?: 1),
            firstTimestamp, maxOf(timestamp, queued?.lastTimestamp ?: timestamp))
        bodyBytes += size(snapshot)
        trim()
    }

    @Synchronized fun drain(limit: Int = 64): List<Event> {
        require(limit > 0)
        val result = ArrayList<Event>(minOf(limit, pending.size))
        val iterator = pending.entries.iterator()
        while (iterator.hasNext() && result.size < limit) {
            val event = iterator.next().value
            result += event
            bodyBytes -= size(event.info)
            iterator.remove()
        }
        return result
    }

    @Synchronized fun retry(events: List<Event>) {
        for (event in events) {
            if (event.attempts >= 2) continue
            val newer = pending[event.info.requestId]
            if (newer == null && pending.size >= maxPending) continue
            var merged = merge(event.info, newer?.info ?: event.info)
            val retained = newer?.let { size(it.info) } ?: 0L
            if (size(merged) - retained > maxBodyBytes - bodyBytes) {
                merged = merged.copy(requestBody = newer?.info?.requestBody, responseBody = newer?.info?.responseBody)
            }
            bodyBytes -= retained
            pending[event.info.requestId] = (newer ?: event).copy(info = merged,
                count = maxOf(event.count, newer?.count ?: 1), timestamp = minOf(event.timestamp, newer?.timestamp ?: event.timestamp),
                lastTimestamp = maxOf(event.lastTimestamp, newer?.lastTimestamp ?: event.lastTimestamp),
                blocked = event.blocked || newer?.blocked == true, rule = newer?.rule ?: event.rule,
                ruleType = newer?.ruleType ?: event.ruleType, attempts = event.attempts + 1)
            bodyBytes += size(merged)
        }
    }

    private fun merge(previous: BlockedRequest?, incoming: BlockedRequest): BlockedRequest = incoming.copy(
        requestBody = incoming.requestBody ?: previous?.requestBody,
        responseBody = incoming.responseBody ?: previous?.responseBody,
        requestHeaders = incoming.requestHeaders ?: previous?.requestHeaders,
        responseCode = incoming.responseCode.takeIf { it >= 0 } ?: previous?.responseCode ?: -1,
        responseHeaders = incoming.responseHeaders ?: previous?.responseHeaders,
        responseMessage = incoming.responseMessage ?: previous?.responseMessage,
        responseBodyContentType = incoming.responseBodyContentType ?: previous?.responseBodyContentType)

    private fun fields(info: BlockedRequest, blocked: Boolean) = 1 or (if (info.requestBody != null) 2 else 0) or
        (if (info.responseCode >= 0) 4 else 0) or (if (info.responseBody != null) 8 else 0) or (if (blocked) 16 else 0)
    private fun size(info: BlockedRequest) = (info.requestBody?.size ?: 0).toLong() + (info.responseBody?.size ?: 0)
    private fun trim() {
        while (pending.size > maxPending) {
            val entry = pending.entries.iterator().next()
            bodyBytes -= size(entry.value.info)
            pending.remove(entry.key)
            // Normal transactions retain their original ID. Permit later completion to requeue an evicted row.
            if (seen[entry.key]?.group == null) seen.remove(entry.key)
        }
    }
    private fun prune(time: Long) {
        while (seen.isNotEmpty() && (seen.size >= 5000 || time - seen.entries.first().value.updated >= 600_000)) {
            seen.remove(seen.entries.first().key)
        }
        while (groups.isNotEmpty() && (groups.size >= 4096 || time - groups.entries.first().value.updated >= 600_000)) {
            groups.remove(groups.entries.first().key)
        }
    }
}
