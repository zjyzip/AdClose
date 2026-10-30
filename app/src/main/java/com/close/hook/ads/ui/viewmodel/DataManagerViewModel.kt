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
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

sealed class ExportEvent {
    data class Ready(val fileName: String, val content: ByteArray) : ExportEvent()
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

    private val exportEvents = Channel<ExportEvent>(Channel.BUFFERED)
    val exportEvent = exportEvents.receiveAsFlow()

    private val viewFileEvents = Channel<ViewFileEvent>(Channel.BUFFERED)
    val viewFileEvent = viewFileEvents.receiveAsFlow()

    private val gson = GsonBuilder().setPrettyPrinting().create()

    init {
        loadAllData()
    }

    fun loadAllData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val frameworkInfoDeferred = async { repository.getFrameworkInfo() }
                val managedFilesDeferred = async { repository.getManagedFiles() }
                val preferenceGroupsDeferred = async { repository.getPreferenceGroups() }
                val databasesDeferred = async { repository.getDatabases() }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        frameworkInfo = frameworkInfoDeferred.await(),
                        managedFiles = managedFilesDeferred.await(),
                        preferenceGroups = preferenceGroupsDeferred.await(),
                        databases = databasesDeferred.await()
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false) }
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
                viewFileEvents.send(ViewFileEvent.Failed("Database file cannot be viewed as text."))
                return@launch
            }

            _uiState.update { it.copy(isLoading = true) }
            try {
                val contentBytes = getItemContent(item)
                _uiState.update { it.copy(isLoading = false) }
                if (contentBytes != null) {
                    val contentStr = String(contentBytes, StandardCharsets.UTF_8)
                    val formattedContent = runCatching {
                        if (contentStr.trim().startsWith("{") || contentStr.trim().startsWith("[")) {
                            gson.toJson(JsonParser.parseString(contentStr))
                        } else contentStr
                    }.getOrDefault(contentStr)
                    viewFileEvents.send(ViewFileEvent.Success(formattedContent, item.name))
                } else {
                    viewFileEvents.send(ViewFileEvent.Failed("Failed to load file content."))
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
                viewFileEvents.send(ViewFileEvent.Failed("Failed to load file content: ${e.message}"))
            }
        }
    }

    fun prepareExport(item: ManagedItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val content = getItemContent(item)
            _uiState.update { it.copy(isLoading = false) }

            if (content != null) {
                val exportFileName = when (item.type) {
                    ItemType.PREFERENCE -> "${item.name}.xml"
                    else -> item.name
                }
                exportEvents.send(ExportEvent.Ready(exportFileName, content))
            } else {
                exportEvents.send(ExportEvent.Failed)
            }
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
