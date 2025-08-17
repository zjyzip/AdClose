package com.close.hook.ads.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.model.LogEntry
import com.close.hook.ads.data.repository.LogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class LogViewModel(private val packageName: String?) : ViewModel() {

    private val _allLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    private val _searchQuery = MutableStateFlow("")

    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val logs: StateFlow<List<LogEntry>> = 
        combine(_searchQuery, _allLogs) { query, logs ->
            if (query.isBlank()) {
                logs
            } else {
                logs.filter {
                    it.tag.contains(query, ignoreCase = true) ||
                    it.message.contains(query, ignoreCase = true)
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        loadLogs()

        LogRepository.logFlow
            .onEach(::processNewLogs)
            .launchIn(viewModelScope)
    }

    private fun processNewLogs(newLogs: List<LogEntry>) {
        val filteredLogs = newLogs.filter { packageName == null || it.packageName == packageName }
        
        if (filteredLogs.isNotEmpty()) {
            _allLogs.update { currentLogs ->
                filteredLogs + currentLogs
            }
        }
    }

    fun loadLogs() {
        _allLogs.value = LogRepository.getLogsForPackage(packageName)
    }

    fun clearLogs() {
        LogRepository.clearLogs()
        _allLogs.value = emptyList()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val packageName: String?) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LogViewModel(packageName) as T
    }
}
