package com.close.hook.ads.data

import com.close.hook.ads.data.model.RequestInfo

/** Small handoff cache so a detail activity can catch completion between a row tap and receiver registration. */
internal object RequestDetailUpdates {
    private data class Key(val packageName: String, val id: String)
    private val latest = LinkedHashMap<Key, RequestInfo>(16, 0.75f, true)

    @Synchronized fun update(item: RequestInfo): RequestInfo {
        if (item.requestId.isEmpty()) return item
        val key = Key(item.packageName, item.requestId)
        val previous = latest[key]
        val merged = if (previous == null) item else RequestListReducer.append(listOf(previous), item).single()
        latest[key] = merged
        while (latest.size > 32) latest.remove(latest.entries.iterator().next().key)
        return merged
    }

    @Synchronized fun current(item: RequestInfo): RequestInfo =
        latest[Key(item.packageName, item.requestId)]?.let { RequestListReducer.append(listOf(item), it).single() } ?: item
}
