package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.model.FrameworkInfo
import com.close.hook.ads.data.model.ManagedItem
import com.close.hook.ads.data.repository.DataManagerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DataManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DataManagerRepository(application)

    private val _uiState = MutableStateFlow(DataManagerUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadAllData()
    }

    fun loadAllData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            val frameworkInfo = repository.getFrameworkInfo()
            val managedFiles = repository.getManagedFiles()
            val preferenceGroups = repository.getPreferenceGroups()

            _uiState.update {
                it.copy(
                    isLoading = false,
                    frameworkInfo = frameworkInfo,
                    managedFiles = managedFiles,
                    preferenceGroups = preferenceGroups
                )
            }
        }
    }

    fun deleteFile(fileName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            if (repository.deleteFile(fileName)) {
                loadAllData()
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun deletePreferenceGroup(groupName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            if (repository.deletePreferenceGroup(groupName)) {
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
    val preferenceGroups: List<ManagedItem> = emptyList()
)
