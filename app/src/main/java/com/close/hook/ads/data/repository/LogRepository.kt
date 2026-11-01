package com.close.hook.ads.data.repository

import com.close.hook.ads.data.model.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object LogRepository {
    const val MAX_CACHE_SIZE = 1000
    private val lock = Any()
    private val cache = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = cache.asStateFlow()

    suspend fun addLogs(logEntries: List<LogEntry>) {
        if (logEntries.isEmpty()) return
        synchronized(lock) {
            cache.value = (logEntries + cache.value).distinctBy { it.id }
                .sortedByDescending { it.timestamp }.take(MAX_CACHE_SIZE)
        }
    }

    fun getLogsForPackage(packageName: String?): List<LogEntry> =
        cache.value.filter { belongsToHookScope(it, packageName) }

    fun belongsToHookScope(entry: LogEntry, packageName: String?): Boolean =
        if (packageName == null) entry.hookScope == LogEntry.GLOBAL_HOOK_SCOPE
        else entry.packageName == packageName && entry.hookScope == packageName

    fun clearLogs() {
        synchronized(lock) { cache.value = emptyList() }
    }

    fun clearLogsForPackage(packageName: String?): List<LogEntry> = synchronized(lock) {
        val removed = cache.value.filter { belongsToHookScope(it, packageName) }
        cache.value = cache.value.filterNot { belongsToHookScope(it, packageName) }
        removed
    }

    fun restoreLogs(entries: List<LogEntry>) {
        synchronized(lock) {
            // Current entries take precedence; logs received after clear are retained.
            cache.value = (cache.value + entries).distinctBy { it.id }
                .sortedByDescending { it.timestamp }.take(MAX_CACHE_SIZE)
        }
    }
}
