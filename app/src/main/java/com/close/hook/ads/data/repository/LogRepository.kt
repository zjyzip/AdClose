package com.close.hook.ads.data.repository

import com.close.hook.ads.data.model.LogEntry
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.CopyOnWriteArrayList

object LogRepository {

    private val _logFlow = MutableSharedFlow<List<LogEntry>>(
        replay = 1,
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val logFlow = _logFlow.asSharedFlow()

    private val logCache = CopyOnWriteArrayList<LogEntry>()
    private const val MAX_CACHE_SIZE = 1000

    suspend fun addLogs(logEntries: List<LogEntry>) {
        val logsToAdd = logEntries.reversed()
        logCache.addAll(0, logsToAdd)
        
        if (logCache.size > MAX_CACHE_SIZE) {
            logCache.subList(MAX_CACHE_SIZE, logCache.size).clear()
        }
        
        _logFlow.emit(logsToAdd)
    }

    fun getLogsForPackage(packageName: String?): List<LogEntry> {
        return if (packageName == null) logCache.toList()
        else logCache.filter { it.packageName == packageName }
    }
    
    fun clearLogs() {
        logCache.clear()
        _logFlow.tryEmit(emptyList())
    }
}
