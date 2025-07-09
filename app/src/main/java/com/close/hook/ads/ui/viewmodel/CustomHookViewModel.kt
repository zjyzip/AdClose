package com.close.hook.ads.ui.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.R
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.model.HookMethodType
import com.close.hook.ads.data.repository.CustomHookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

class CustomHookViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CustomHookRepository(application)
    private var currentPackageName: String? = null

    private val _hookConfigs = MutableStateFlow<List<CustomHookInfo>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    private val _selectedConfigs = MutableStateFlow<Set<CustomHookInfo>>(emptySet())
    private val _isLoading = MutableStateFlow(true)
    private val _isGlobalEnabled = MutableStateFlow(false)

    val isGlobalEnabled: StateFlow<Boolean> = _isGlobalEnabled.asStateFlow()

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
                    config.hookPoint?.lowercase()?.contains(lowerCaseQuery) == true
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val selectedConfigs: StateFlow<Set<CustomHookInfo>> = _selectedConfigs.asStateFlow()
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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

    fun addHook(hookConfig: CustomHookInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val configWithPackage = hookConfig.copy(packageName = currentPackageName)
            val updatedList = _hookConfigs.value.toMutableList().apply { add(configWithPackage) }
            saveAndRefreshHooks(updatedList)
        }
    }

    fun updateHook(oldConfig: CustomHookInfo, newConfig: CustomHookInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedList = _hookConfigs.value.toMutableList()
            val index = updatedList.indexOfFirst { it.id == oldConfig.id }
            if (index != -1) {
                updatedList[index] = newConfig.copy(id = oldConfig.id, isEnabled = oldConfig.isEnabled, packageName = currentPackageName)
                saveAndRefreshHooks(updatedList)
            }
        }
    }

    fun toggleHookActivation(hookConfig: CustomHookInfo, isEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedList = _hookConfigs.value.toMutableList()
            val index = updatedList.indexOfFirst { it.id == hookConfig.id }
            if (index != -1) {
                updatedList[index] = hookConfig.copy(isEnabled = isEnabled, packageName = currentPackageName)
                saveAndRefreshHooks(updatedList)
            }
        }
    }

    fun deleteHook(hookConfig: CustomHookInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedList = _hookConfigs.value.toMutableList().apply { removeIf { it.id == hookConfig.id } }
            saveAndRefreshHooks(updatedList)
            _selectedConfigs.value = _selectedConfigs.value.minus(hookConfig)
        }
    }

    fun clearAllHooks() {
        viewModelScope.launch(Dispatchers.IO) {
            saveAndRefreshHooks(emptyList())
            _selectedConfigs.value = emptySet()
        }
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

    fun deleteSelectedHookConfigs() {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedList = _hookConfigs.value.toMutableList()
            updatedList.removeAll(_selectedConfigs.value)
            saveAndRefreshHooks(updatedList)
            _selectedConfigs.value = emptySet()
        }
    }

    suspend fun copySelectedHooksToJson(): String = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        if (_selectedConfigs.value.isEmpty()) {
            return@withContext context.getString(R.string.no_hook_selected_to_copy)
        }

        val configsToCopy = _selectedConfigs.value.map { it.copy(packageName = currentPackageName) }
        val jsonString = Json.encodeToString(configsToCopy)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(context.getString(R.string.hook_configurations_label), jsonString)
        clipboard.setPrimaryClip(clip)
        return@withContext context.getString(R.string.copied_hooks_count, _selectedConfigs.value.size)
    }

    fun getExportableHooks(): List<CustomHookInfo> {
        return _hookConfigs.value.map { it.copy(packageName = currentPackageName) }
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

    suspend fun getAllHooksBlocking(): List<CustomHookInfo> {
        return _hookConfigs.first()
    }

    private suspend fun saveAndRefreshHooks(configs: List<CustomHookInfo>) {
        repository.saveHookConfigs(currentPackageName, configs)
        _hookConfigs.value = configs.toList()
    }

    suspend fun handleImport(importedConfigs: List<CustomHookInfo>): ImportResult {
        val isCurrentPageGlobal = currentPackageName.isNullOrEmpty()
        val containsGlobalConfigs = importedConfigs.any { it.packageName.isNullOrEmpty() }
        val containsAppSpecificConfigs = importedConfigs.any { !it.packageName.isNullOrEmpty() }
        val context = getApplication<Application>()

        return when {
            !isCurrentPageGlobal && containsGlobalConfigs -> {
                ImportResult.ShowGlobalRulesImportDialog(importedConfigs)
            }
            isCurrentPageGlobal && containsAppSpecificConfigs -> {
                val globalOnlyConfigs = importedConfigs.filter { it.packageName.isNullOrEmpty() }
                val updated = importHooks(globalOnlyConfigs)
                ImportResult.ShowAppSpecificRulesSkippedSnackbar(importedConfigs.count { !it.packageName.isNullOrEmpty() }, updated)
            }
            else -> {
                val updated = importHooks(importedConfigs)
                ImportResult.ShowSnackbar(updated)
            }
        }
    }

    suspend fun confirmGlobalHooksImport(importedConfigs: List<CustomHookInfo>): Boolean {
        val globalOnlyImported = importedConfigs.filter { it.packageName.isNullOrEmpty() }
        return importGlobalHooksToStorage(globalOnlyImported)
    }
}

sealed class ImportResult {
    data class ShowSnackbar(val isUpdated: Boolean) : ImportResult()
    data class ShowDialog(val titleResId: Int, val message: String, val throwable: Throwable? = null) : ImportResult()
    data class ShowGlobalRulesImportDialog(val importedConfigs: List<CustomHookInfo>) : ImportResult()
    data class ShowAppSpecificRulesSkippedSnackbar(val skippedCount: Int, val isUpdated: Boolean) : ImportResult()
}
