package com.close.hook.ads.ui.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.repository.CustomHookRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class CustomHookViewModel(
    application: Application,
    private val currentPackageName: String?
) : AndroidViewModel(application) {

    private val repository = CustomHookRepository(application)

    private val _hookConfigs = MutableStateFlow<List<CustomHookInfo>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    private val _isLoading = MutableStateFlow(true)
    private val _isGlobalEnabled = MutableStateFlow(false)
    private val _autoDetectedHooksResult = MutableStateFlow<List<CustomHookInfo>?>(null)

    val isGlobalEnabled: StateFlow<Boolean> = _isGlobalEnabled.asStateFlow()
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val autoDetectedHooksResult: StateFlow<List<CustomHookInfo>?> = _autoDetectedHooksResult.asStateFlow()

    val filteredConfigs: StateFlow<List<CustomHookInfo>> =
        combine(_hookConfigs, _searchQuery) { configs, currentQuery ->
            if (currentQuery.isBlank()) {
                configs
            } else {
                val lowerCaseQuery = currentQuery.lowercase()
                configs.filter { config ->
                    listOfNotNull(
                        config.className,
                        config.returnValue,
                        config.fieldName,
                        config.fieldValue,
                        config.hookPoint,
                        config.hookMethodType.displayName,
                        config.methodNames?.joinToString(","),
                        config.parameterTypes?.joinToString(","),
                        config.searchStrings?.joinToString(",")
                    ).any { it.lowercase().contains(lowerCaseQuery) }
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val prettyJson = Json { prettyPrint = true }

    init {
        loadHookConfigs()
        loadGlobalHookStatus()
    }

    private fun loadHookConfigs() {
        viewModelScope.launch {
            _isLoading.value = true
            _hookConfigs.value = repository.getHookConfigs(currentPackageName)
            _isLoading.value = false
        }
    }

    private fun loadGlobalHookStatus() {
        viewModelScope.launch {
            _isGlobalEnabled.value = repository.getHookEnabledStatus(currentPackageName)
        }
    }

    fun setGlobalHookStatus(isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setHookEnabledStatus(currentPackageName, isEnabled)
            _isGlobalEnabled.value = isEnabled
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun handleAutoDetectResult(hooks: List<CustomHookInfo>) {
        _autoDetectedHooksResult.value = hooks
    }

    fun clearAutoDetectHooksResult() {
        _autoDetectedHooksResult.value = null
    }

    fun requestAutoDetectHooks(packageName: String) {
        val intent = Intent("com.close.hook.ads.ACTION_AUTO_DETECT_ADS").apply {
            putExtra("target_package", packageName)
            setPackage(packageName)
        }
        getApplication<Application>().sendBroadcast(intent)
    }

    fun addHook(hookConfig: CustomHookInfo) {
        updateAndSaveConfigs { currentList ->
            val newConfigWithPackage = hookConfig.copy(
                id = UUID.randomUUID().toString(),
                packageName = currentPackageName
            )
            listOf(newConfigWithPackage) + currentList
        }
    }

    fun updateHook(oldConfig: CustomHookInfo, newConfig: CustomHookInfo) {
        updateAndSaveConfigs { currentList ->
            currentList.map {
                if (it.id == oldConfig.id) {
                    newConfig.copy(id = oldConfig.id, packageName = currentPackageName)
                } else {
                    it
                }
            }
        }
    }

    fun toggleHookActivation(hookConfig: CustomHookInfo, isEnabled: Boolean) {
        updateAndSaveConfigs { currentList ->
            currentList.map {
                if (it.id == hookConfig.id) it.copy(isEnabled = isEnabled) else it
            }
        }
    }

    fun deleteHook(hookConfig: CustomHookInfo) {
        updateAndSaveConfigs { currentList ->
            currentList.filter { it.id != hookConfig.id }
        }
    }

    fun clearAllHooks() {
        updateAndSaveConfigs { emptyList() }
    }

    suspend fun deleteHookConfigs(configsToDelete: List<CustomHookInfo>): Int {
        val idsToDelete = configsToDelete.map { it.id }.toSet()
        var deletedCount = 0

        _hookConfigs.update { currentList ->
            val originalSize = currentList.size
            val updatedList = currentList.filterNot { idsToDelete.contains(it.id) }
            deletedCount = originalSize - updatedList.size
            updatedList
        }
        
        repository.saveHookConfigs(currentPackageName, _hookConfigs.value)
        
        return deletedCount
    }

    fun getJsonStringForConfigs(configs: List<CustomHookInfo>): String {
        return prettyJson.encodeToString(configs)
    }

    fun getExportableHooks(): List<CustomHookInfo> = _hookConfigs.value

    private fun updateConfigsList(
        currentConfigs: MutableList<CustomHookInfo>,
        importedConfigs: List<CustomHookInfo>,
        targetPackageName: String?
    ): Boolean {
        val currentConfigsMap = currentConfigs.associateBy { it.id }.toMutableMap()
        var hasBeenUpdated = false

        importedConfigs.forEach { importedConfig ->
            val configWithDefaults = importedConfig.copy(
                id = importedConfig.id ?: UUID.randomUUID().toString(),
                packageName = targetPackageName
            )
            val existingConfig = currentConfigsMap[configWithDefaults.id]

            if (existingConfig == null || existingConfig != configWithDefaults) {
                currentConfigsMap[configWithDefaults.id!!] = configWithDefaults
                hasBeenUpdated = true
            }
        }

        if (hasBeenUpdated) {
            currentConfigs.clear()
            currentConfigs.addAll(currentConfigsMap.values)
        }
        return hasBeenUpdated
    }

    fun importHooks(importedConfigs: List<CustomHookInfo>) {
        updateAndSaveConfigs { currentList ->
            val mutableCurrentList = currentList.toMutableList()
            updateConfigsList(mutableCurrentList, importedConfigs, currentPackageName)
            mutableCurrentList
        }
    }

    suspend fun importGlobalHooksToStorage(globalConfigs: List<CustomHookInfo>): Boolean {
        val existingGlobalConfigs = repository.getHookConfigs(null).toMutableList()
        val globalOnlyConfigsProcessed = globalConfigs.map { it.copy(packageName = null) }
        val updated = updateConfigsList(existingGlobalConfigs, globalOnlyConfigsProcessed, null)

        if (updated) {
            repository.saveHookConfigs(null, existingGlobalConfigs)
            if (currentPackageName == null) {
                _hookConfigs.value = existingGlobalConfigs
            }
        }
        return updated
    }

    fun getTargetPackageName(): String? = currentPackageName

    private fun updateAndSaveConfigs(transform: (List<CustomHookInfo>) -> List<CustomHookInfo>) {
        viewModelScope.launch {
            _hookConfigs.update(transform)
            repository.saveHookConfigs(currentPackageName, _hookConfigs.value)
        }
    }

    suspend fun getImportAction(importedConfigs: List<CustomHookInfo>): ImportAction {
        val isCurrentPageGlobal = currentPackageName.isNullOrEmpty()
        val containsGlobalConfigs = importedConfigs.any { it.packageName.isNullOrEmpty() }
        val containsAppSpecificConfigs = importedConfigs.any { !it.packageName.isNullOrEmpty() }

        return when {
            !isCurrentPageGlobal && containsGlobalConfigs -> ImportAction.PromptImportGlobal(importedConfigs)
            isCurrentPageGlobal && containsAppSpecificConfigs -> {
                val globalOnlyConfigs = importedConfigs.filter { it.packageName.isNullOrEmpty() }
                val skippedAppSpecificCount = importedConfigs.count { !it.packageName.isNullOrEmpty() }
                ImportAction.ImportGlobalAndNotifySkipped(globalOnlyConfigs, skippedAppSpecificCount)
            }
            else -> ImportAction.DirectImport(importedConfigs)
        }
    }

    class Factory(
        private val application: Application,
        private val packageName: String?
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(CustomHookViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return CustomHookViewModel(application, packageName) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

sealed class ImportAction {
    data class DirectImport(val configs: List<CustomHookInfo>) : ImportAction()
    data class PromptImportGlobal(val configs: List<CustomHookInfo>) : ImportAction()
    data class ImportGlobalAndNotifySkipped(val globalConfigs: List<CustomHookInfo>, val skippedAppSpecificCount: Int) : ImportAction()
}
