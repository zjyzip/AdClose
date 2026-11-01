package com.close.hook.ads.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.model.LogEntry
import com.close.hook.ads.data.repository.LogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class LogViewModel(private val packageName: String?) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    val logs = combine(LogRepository.logs, _searchQuery) { entries, query ->
        entries.filter { entry ->
            LogRepository.belongsToHookScope(entry, packageName) && (query.isBlank() ||
                entry.tag.contains(query, ignoreCase = true) || entry.message.contains(query, ignoreCase = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearLogs(): List<LogEntry> = LogRepository.clearLogsForPackage(packageName)
    fun restoreLogs(entries: List<LogEntry>) = LogRepository.restoreLogs(entries)
    fun setSearchQuery(query: String) { _searchQuery.value = query }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val packageName: String?) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LogViewModel(packageName) as T
    }
}
