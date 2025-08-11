package com.close.hook.ads.ui.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.R
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.repository.CustomHookRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

    val isGlobalEnabled: StateFlow<Boolean> = _isGlobalEnabled.asStateFlow()
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _autoDetectedHooksResult = MutableStateFlow<List<CustomHookInfo>?>(null)
    val autoDetectedHooksResult: StateFlow<List<CustomHookInfo>?> = _autoDetectedHooksResult.asStateFlow()

    val filteredConfigs: StateFlow<List<CustomHookInfo>> =
        combine(_hookConfigs, _searchQuery) { configs, currentQuery ->
            if (currentQuery.isBlank()) {
                configs
            } else {
                val lowerCaseQuery = currentQuery.lowercase()
                configs.filter { config ->
                    config.className.lowercase().contains(lowerCaseQuery) ||
                            config.methodNames?.any { it.lowercase().contains(lowerCaseQuery) } == true ||
                            config.returnValue?.lowercase()?.contains(lowerCaseQuery) == true ||
                            config.parameterTypes?.any { it.lowercase().contains(lowerCaseQuery) } == true ||
                            config.fieldName?.lowercase()?.contains(lowerCaseQuery) == true ||
                            config.fieldValue?.lowercase()?.contains(lowerCaseQuery) == true ||
                            config.hookPoint?.lowercase()?.contains(lowerCaseQuery) == true ||
                            config.searchStrings?.any { it.lowercase().contains(lowerCaseQuery) } == true
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
        _isLoading.value = true
        viewModelScope.launch {
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

    suspend fun addHook(hookConfig: CustomHookInfo) {
        val newConfigWithPackage = hookConfig.copy(
            id = UUID.randomUUID().toString(),
            packageName = currentPackageName
        )
        val updatedList = _hookConfigs.value.toMutableList().apply { add(newConfigWithPackage) }
        saveAndRefreshHooks(updatedList)
    }

    suspend fun updateHook(oldConfig: CustomHookInfo, newConfig: CustomHookInfo) {
        val updatedList = _hookConfigs.value.toMutableList()
        val index = updatedList.indexOfFirst { it.id == oldConfig.id }
        if (index != -1) {
            updatedList[index] = newConfig.copy(id = oldConfig.id, packageName = currentPackageName)
            saveAndRefreshHooks(updatedList)
        }
    }

    suspend fun toggleHookActivation(hookConfig: CustomHookInfo, isEnabled: Boolean) {
        val updatedList = _hookConfigs.value.toMutableList()
        val index = updatedList.indexOfFirst { it.id == hookConfig.id }
        if (index != -1) {
            updatedList[index] = hookConfig.copy(isEnabled = isEnabled, packageName = currentPackageName)
            saveAndRefreshHooks(updatedList)
        }
    }

    suspend fun deleteHook(hookConfig: CustomHookInfo) {
        val updatedList = _hookConfigs.value.toMutableList().apply { removeIf { it.id == hookConfig.id } }
        saveAndRefreshHooks(updatedList)
    }

    suspend fun clearAllHooks() {
        saveAndRefreshHooks(emptyList())
    }

    suspend fun deleteHookConfigs(configsToDelete: List<CustomHookInfo>): Int {
        val updatedList = _hookConfigs.value.toMutableList()
        val deletedCount = configsToDelete.size
        updatedList.removeAll(configsToDelete)
        saveAndRefreshHooks(updatedList)
        return deletedCount
    }

    fun copyHooksToJson(configsToCopy: List<CustomHookInfo>): String? {
        val context = getApplication<Application>()
        return try {
            val jsonString = prettyJson.encodeToString(configsToCopy)

            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(context.getString(R.string.hook_configurations_label), jsonString)
            clipboard.setPrimaryClip(clip)
            context.getString(R.string.copied_hooks_count, configsToCopy.size)
        } catch (e: Exception) {
            "Error copying hooks: ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    fun getExportableHooks(): List<CustomHookInfo> {
        return _hookConfigs.value.toList()
    }

    private fun updateConfigsList(
        currentConfigs: MutableList<CustomHookInfo>,
        importedConfigs: List<CustomHookInfo>,
        targetPackageName: String?
    ): Boolean {
        var updated = false
        for (importedConfig in importedConfigs) {
            val configToAddOrUpdate = importedConfig.copy(
                id = importedConfig.id ?: UUID.randomUUID().toString(),
                packageName = targetPackageName
            )
            val existingConfigIndex = currentConfigs.indexOfFirst { it.id == configToAddOrUpdate.id }

            if (existingConfigIndex == -1) {
                currentConfigs.add(configToAddOrUpdate)
                updated = true
            } else if (currentConfigs[existingConfigIndex] != configToAddOrUpdate) {
                currentConfigs[existingConfigIndex] = configToAddOrUpdate
                updated = true
            }
        }
        return updated
    }

    suspend fun importHooks(importedConfigs: List<CustomHookInfo>): Boolean {
        val currentConfigs = _hookConfigs.value.toMutableList()
        val updated = updateConfigsList(currentConfigs, importedConfigs, currentPackageName)

        if (updated) {
            saveAndRefreshHooks(currentConfigs)
        }
        return updated
    }

    suspend fun importGlobalHooksToStorage(globalConfigs: List<CustomHookInfo>): Boolean {
        val existingGlobalConfigs = repository.getHookConfigs(null).toMutableList()
        val globalOnlyConfigsProcessed = globalConfigs.map { it.copy(packageName = null) }
        val updated = updateConfigsList(existingGlobalConfigs, globalOnlyConfigsProcessed, null)

        if (updated) {
            repository.saveHookConfigs(null, existingGlobalConfigs)
        }
        return updated
    }

    fun getTargetPackageName(): String? = currentPackageName

    private suspend fun saveAndRefreshHooks(configs: List<CustomHookInfo>) {
        repository.saveHookConfigs(currentPackageName, configs)
        _hookConfigs.value = configs.toList()
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
