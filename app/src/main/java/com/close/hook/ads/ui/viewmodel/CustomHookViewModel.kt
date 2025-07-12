package com.close.hook.ads.ui.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.R
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.repository.CustomHookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class CustomHookViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CustomHookRepository(application)
    private var currentPackageName: String? = null

    private val _hookConfigs = MutableStateFlow<List<CustomHookInfo>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    private val _selectedConfigs = MutableStateFlow<Set<CustomHookInfo>>(emptySet())
    private val _isLoading = MutableStateFlow(true)
    private val _isGlobalEnabled = MutableStateFlow(false)

    val isGlobalEnabled: StateFlow<Boolean> = _isGlobalEnabled.asStateFlow()
    val selectedConfigs: StateFlow<Set<CustomHookInfo>> = _selectedConfigs.asStateFlow()
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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

    fun init(packageName: String?) {
        currentPackageName = packageName
        loadHookConfigs()
        loadGlobalHookStatus()
    }

    private fun loadHookConfigs() {
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            _hookConfigs.value = repository.getHookConfigs(currentPackageName)
            _isLoading.value = false
        }
    }

    private fun loadGlobalHookStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _isGlobalEnabled.value = repository.getHookEnabledStatus(currentPackageName)
        }
    }

    fun setGlobalHookStatus(isEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setHookEnabledStatus(currentPackageName, isEnabled)
            _isGlobalEnabled.value = isEnabled
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    suspend fun addHook(hookConfig: CustomHookInfo) = withContext(Dispatchers.IO) {
        val newConfigWithPackage = hookConfig.copy(
            id = UUID.randomUUID().toString(),
            packageName = currentPackageName
        )
        val updatedList = _hookConfigs.value.toMutableList().apply { add(newConfigWithPackage) }
        saveAndRefreshHooks(updatedList)
    }

    suspend fun updateHook(oldConfig: CustomHookInfo, newConfig: CustomHookInfo) = withContext(Dispatchers.IO) {
        val updatedList = _hookConfigs.value.toMutableList()
        val index = updatedList.indexOfFirst { it.id == oldConfig.id }
        if (index != -1) {
            updatedList[index] = newConfig.copy(id = oldConfig.id, packageName = currentPackageName)
            saveAndRefreshHooks(updatedList)
        }
    }

    suspend fun toggleHookActivation(hookConfig: CustomHookInfo, isEnabled: Boolean) = withContext(Dispatchers.IO) {
        val updatedList = _hookConfigs.value.toMutableList()
        val index = updatedList.indexOfFirst { it.id == hookConfig.id }
        if (index != -1) {
            updatedList[index] = hookConfig.copy(isEnabled = isEnabled, packageName = currentPackageName)
            saveAndRefreshHooks(updatedList)
        }
    }

    suspend fun deleteHook(hookConfig: CustomHookInfo) = withContext(Dispatchers.IO) {
        val updatedList = _hookConfigs.value.toMutableList().apply { removeIf { it.id == hookConfig.id } }
        saveAndRefreshHooks(updatedList)
        _selectedConfigs.value = _selectedConfigs.value.minus(hookConfig)
    }

    suspend fun clearAllHooks() = withContext(Dispatchers.IO) {
        saveAndRefreshHooks(emptyList())
        _selectedConfigs.value = emptySet()
    }

    fun toggleSelection(hookConfig: CustomHookInfo) {
        _selectedConfigs.value = if (_selectedConfigs.value.contains(hookConfig)) {
            _selectedConfigs.value.minus(hookConfig)
        } else {
            _selectedConfigs.value.plus(hookConfig)
        }
    }

    fun clearSelection() {
        _selectedConfigs.value = emptySet()
    }

    suspend fun deleteSelectedHookConfigs(): Int = withContext(Dispatchers.IO) {
        val updatedList = _hookConfigs.value.toMutableList()
        val deletedCount = _selectedConfigs.value.size
        updatedList.removeAll(_selectedConfigs.value)
        saveAndRefreshHooks(updatedList)
        _selectedConfigs.value = emptySet()
        deletedCount
    }

    fun copySelectedHooksToJson(): String? {
        val context = getApplication<Application>()
        if (_selectedConfigs.value.isEmpty()) {
            return context.getString(R.string.no_hook_selected_to_copy)
        }

        return try {
            val configsToCopy = _selectedConfigs.value.toList()
            val jsonString = prettyJson.encodeToString(configsToCopy)

            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(context.getString(R.string.hook_configurations_label), jsonString)
            clipboard.setPrimaryClip(clip)
            context.getString(R.string.copied_hooks_count, _selectedConfigs.value.size)
        } catch (e: Exception) {
            "Error copying hooks: ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    fun getExportableHooks(): List<CustomHookInfo> {
        return _hookConfigs.value.toList()
    }

    suspend fun importHooks(importedConfigs: List<CustomHookInfo>): Boolean = withContext(Dispatchers.IO) {
        val currentConfigs = _hookConfigs.value.toMutableList()
        var updated = false

        for (importedConfig in importedConfigs) {
            val configForCurrentContext = importedConfig.copy(packageName = currentPackageName)
            val existingConfigIndex = currentConfigs.indexOfFirst { it.id == configForCurrentContext.id }

            if (existingConfigIndex == -1) {
                currentConfigs.add(configForCurrentContext)
                updated = true
            } else if (currentConfigs[existingConfigIndex] != configForCurrentContext) {
                currentConfigs[existingConfigIndex] = configForCurrentContext
                updated = true
            }
        }

        if (updated) {
            saveAndRefreshHooks(currentConfigs)
        }
        return@withContext updated
    }

    suspend fun importGlobalHooksToStorage(globalConfigs: List<CustomHookInfo>): Boolean = withContext(Dispatchers.IO) {
        val existingGlobalConfigs = repository.getHookConfigs(null).toMutableList()
        var updated = false

        for (importedConfig in globalConfigs) {
            val configForGlobal = importedConfig.copy(packageName = null)
            val existingConfigIndex = existingGlobalConfigs.indexOfFirst { it.id == configForGlobal.id }

            if (existingConfigIndex == -1) {
                existingGlobalConfigs.add(configForGlobal)
                updated = true
            } else if (existingGlobalConfigs[existingConfigIndex] != configForGlobal) {
                existingGlobalConfigs[existingConfigIndex] = configForGlobal
                updated = true
            }
        }

        if (updated) {
            repository.saveHookConfigs(null, existingGlobalConfigs)
        }
        return@withContext updated
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
                val skippedCount = importedConfigs.count { !it.packageName.isNullOrEmpty() }
                ImportAction.ImportGlobalAndNotifySkipped(globalOnlyConfigs, skippedCount)
            }

            else -> ImportAction.DirectImport(importedConfigs)
        }
    }
}

sealed class ImportAction {
    data class DirectImport(val configs: List<CustomHookInfo>) : ImportAction()
    data class PromptImportGlobal(val configs: List<CustomHookInfo>) : ImportAction()
    data class ImportGlobalAndNotifySkipped(val globalConfigs: List<CustomHookInfo>, val skippedAppSpecificCount: Int) : ImportAction()
}
