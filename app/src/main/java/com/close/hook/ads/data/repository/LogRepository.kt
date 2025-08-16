package com.close.hook.ads.data.repository

import com.close.hook.ads.data.model.LogEntry
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object LogRepository {

    private val _logFlow = MutableSharedFlow<LogEntry>(
        replay = 0,
        extraBufferCapacity = 1000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val logFlow = _logFlow.asSharedFlow()

    private val logCache = mutableListOf<LogEntry>()
    private const val MAX_CACHE_SIZE = 1000

    suspend fun addLog(logEntry: LogEntry) {
        synchronized(logCache) {
            logCache.add(0, logEntry)
            if (logCache.size > MAX_CACHE_SIZE) {
                logCache.removeLast()
            }
        }
        _logFlow.emit(logEntry)
    }

    fun getLogsForPackage(packageName: String?): List<LogEntry> = synchronized(logCache) {
        if (packageName == null) logCache.toList()
        else logCache.filter { it.packageName == packageName }
    }

    fun clearLogs() = synchronized(logCache) {
        logCache.clear()
    }
}
