package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.model.FrameworkInfo
import com.close.hook.ads.data.model.ItemType
import com.close.hook.ads.data.model.ManagedItem
import com.close.hook.ads.data.repository.DataManagerRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class ExportEvent {
    data class Success(val content: ByteArray, val fileName: String) : ExportEvent()
    object Failed : ExportEvent()
}

class DataManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DataManagerRepository(application)

    private val _uiState = MutableStateFlow(DataManagerUiState())
    val uiState = _uiState.asStateFlow()

    private val _exportEvent = MutableSharedFlow<ExportEvent>()
    val exportEvent = _exportEvent.asSharedFlow()

    init {
        loadAllData()
    }

    fun loadAllData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            val frameworkInfo = repository.getFrameworkInfo()
            val managedFiles = repository.getManagedFiles()
            val preferenceGroups = repository.getPreferenceGroups()
            val databases = repository.getDatabases()

            _uiState.update {
                it.copy(
                    isLoading = false,
                    frameworkInfo = frameworkInfo,
                    managedFiles = managedFiles,
                    preferenceGroups = preferenceGroups,
                    databases = databases
                )
            }
        }
    }
    
    private suspend fun getItemContent(item: ManagedItem): ByteArray? {
        return when (item.type) {
            ItemType.FILE -> repository.getFileContent(item.name)
            ItemType.PREFERENCE -> repository.getPreferenceContent(item.name)
            ItemType.DATABASE -> repository.getDatabaseContent(item.name)
        }
    }

    fun prepareExport(item: ManagedItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val content = getItemContent(item)
            if (content != null) {
                val exportFileName = when (item.type) {
                    ItemType.PREFERENCE -> "${item.name}.xml"
                    else -> item.name
                }
                _exportEvent.emit(ExportEvent.Success(content, exportFileName))
            } else {
                _exportEvent.emit(ExportEvent.Failed)
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun deleteItem(item: ManagedItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val success = when (item.type) {
                ItemType.FILE -> repository.deleteFile(item.name)
                ItemType.PREFERENCE -> repository.deletePreferenceGroup(item.name)
                ItemType.DATABASE -> repository.deleteDatabase(item.name)
            }
            if (success) {
                loadAllData()
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}

data class DataManagerUiState(
    val isLoading: Boolean = true,
    val frameworkInfo: FrameworkInfo? = null,
    val managedFiles: List<ManagedItem> = emptyList(),
    val preferenceGroups: List<ManagedItem> = emptyList(),
    val databases: List<ManagedItem> = emptyList()
)
