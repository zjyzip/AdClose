package com.close.hook.ads.data.repository

import com.close.hook.ads.data.model.LogEntry
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Collections
import java.util.LinkedList

object LogRepository {

    private val _logFlow = MutableSharedFlow<List<LogEntry>>(
        replay = 1,
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val logFlow = _logFlow.asSharedFlow()

    private val logCache = Collections.synchronizedList(LinkedList<LogEntry>())
    private const val MAX_CACHE_SIZE = 1000

    suspend fun addLogs(logEntries: List<LogEntry>) {
        if (logEntries.isEmpty()) return

        val logsToInsert = logEntries.reversed()

        synchronized(logCache) {
            logCache.addAll(0, logsToInsert)

            while (logCache.size > MAX_CACHE_SIZE) {
                logCache.removeAt(logCache.size - 1)
            }
        }
        
        _logFlow.emit(logsToInsert)
    }

    fun getLogsForPackage(packageName: String?): List<LogEntry> {
        synchronized(logCache) {
            return if (packageName == null) {
                ArrayList(logCache)
            } else {
                logCache.filter { it.packageName == packageName }
            }
        }
    }
    
    fun clearLogs() {
        logCache.clear()
        _logFlow.tryEmit(emptyList())
    }
}
