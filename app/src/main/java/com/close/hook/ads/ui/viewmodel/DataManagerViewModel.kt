package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.model.DataManagerState
import com.close.hook.ads.data.model.ItemType
import com.close.hook.ads.data.model.ManagedItem
import com.close.hook.ads.data.repository.DataManagerRepository
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

sealed class ExportEvent {
    data class Ready(val fileName: String) : ExportEvent()
    object Failed : ExportEvent()
}

sealed class ViewFileEvent {
    data class Success(val content: String, val title: String) : ViewFileEvent()
    data class Failed(val error: String) : ViewFileEvent()
}

class DataManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DataManagerRepository(application)

    private val _uiState = MutableStateFlow(DataManagerState())
    val uiState = _uiState.asStateFlow()

    private val _exportEvent = MutableSharedFlow<ExportEvent>()
    val exportEvent = _exportEvent.asSharedFlow()

    private val _viewFileEvent = MutableSharedFlow<ViewFileEvent>()
    val viewFileEvent = _viewFileEvent.asSharedFlow()

    private val gson = GsonBuilder().setPrettyPrinting().create()

    var pendingExportContent: ByteArray? = null
        private set

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

    fun loadFileForView(item: ManagedItem) {
        viewModelScope.launch {
            if (item.type == ItemType.DATABASE) {
                _viewFileEvent.emit(ViewFileEvent.Failed("Database file cannot be viewed as text."))
                return@launch
            }

            _uiState.update { it.copy(isLoading = true) }
            val contentBytes = getItemContent(item)
            _uiState.update { it.copy(isLoading = false) }

            if (contentBytes != null) {
                try {
                    val contentStr = String(contentBytes, StandardCharsets.UTF_8)
                    val formattedContent = try {
                        if (contentStr.trim().startsWith("{") || contentStr.trim().startsWith("[")) {
                            val jsonElement = JsonParser.parseString(contentStr)
                            gson.toJson(jsonElement)
                        } else {
                            contentStr
                        }
                    } catch (e: Exception) {
                        contentStr
                    }
                    _viewFileEvent.emit(ViewFileEvent.Success(formattedContent, item.name))
                } catch (e: Exception) {
                    _viewFileEvent.emit(ViewFileEvent.Failed("Failed to parse file content: ${e.message}"))
                }
            } else {
                _viewFileEvent.emit(ViewFileEvent.Failed("Failed to load file content."))
            }
        }
    }

    fun prepareExport(item: ManagedItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val content = getItemContent(item)
            if (content != null) {
                pendingExportContent = content
                val exportFileName = when (item.type) {
                    ItemType.PREFERENCE -> "${item.name}.xml"
                    else -> item.name
                }
                _exportEvent.emit(ExportEvent.Ready(exportFileName))
            } else {
                _exportEvent.emit(ExportEvent.Failed)
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }
    
    fun clearPendingExport() {
        pendingExportContent = null
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
