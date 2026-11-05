package com.close.hook.ads.hook.util

import com.close.hook.ads.data.model.LogEntry

/** Keep individual logs useful without allowing a batch to exceed Binder's shared buffer. */
internal object LogPayloadPolicy {
    const val MAX_STACK_CHARS = 16 * 1024
    private const val MAX_MESSAGE_CHARS = 16 * 1024
    const val MAX_BATCH_BYTES = 192 * 1024
    private const val TRUNCATED = "\n[truncated]\n"

    fun stack(text: String?): String? {
        if (text == null || text.length <= MAX_STACK_CHARS) return text
        val limit = MAX_STACK_CHARS - TRUNCATED.length
        val newline = text.lastIndexOf('\n', limit - 1)
        val end = if (newline >= 0) newline else safeEnd(text, limit)
        return text.substring(0, end) + TRUNCATED
    }

    fun message(text: String): String =
        if (text.length <= MAX_MESSAGE_CHARS) text
        else text.substring(0, safeEnd(text, MAX_MESSAGE_CHARS - TRUNCATED.length)) + TRUNCATED

    private fun safeEnd(text: String, end: Int): Int =
        if (end > 0 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end - 1 else end

    // Parcelable strings are UTF-16. Reserve fixed overhead for timestamps, lengths and alignment.
    fun estimatedBytes(entry: LogEntry): Int = 512 + 2 * (
        entry.id.length + entry.tag.length + entry.message.length + entry.packageName.length +
            (entry.stackTrace?.length ?: 0) + (entry.hookScope?.length ?: 0))

    fun batches(entries: List<LogEntry>): List<List<LogEntry>> {
        val result = mutableListOf<List<LogEntry>>()
        var batch = mutableListOf<LogEntry>()
        var bytes = 0
        for (entry in entries) {
            val size = estimatedBytes(entry)
            require(size <= MAX_BATCH_BYTES) { "Log entry exceeds payload limit" }
            if (batch.isNotEmpty() && bytes + size > MAX_BATCH_BYTES) {
                result += batch
                batch = mutableListOf()
                bytes = 0
            }
            batch += entry
            bytes += size
        }
        if (batch.isNotEmpty()) result += batch
        return result
    }
}
