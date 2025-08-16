package com.close.hook.ads.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.repository.LogRepository
import com.close.hook.ads.data.model.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

class LogViewModel(private val packageName: String?) : ViewModel() {

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    init {
        _logs.value = LogRepository.getLogsForPackage(packageName)

        LogRepository.logFlow
            .onEach { newLog ->
                if (packageName == null || newLog.packageName == packageName) {
                    _logs.update { listOf(newLog) + it }
                }
            }
            .launchIn(viewModelScope)
    }

    fun clearLogs() {
        LogRepository.clearLogs()
        _logs.value = emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val packageName: String?) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LogViewModel(packageName) as T
        }
    }
}
