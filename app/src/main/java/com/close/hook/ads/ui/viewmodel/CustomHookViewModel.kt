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
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.flow.first

class CustomHookViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CustomHookRepository(application)
    private var currentPackageName: String? = null

    private val _hookList = MutableStateFlow<List<CustomHookInfo>>(emptyList())
    private val _query = MutableStateFlow("")
    private val _selectedHooks = MutableStateFlow<Set<CustomHookInfo>>(emptySet())
    private val _isLoading = MutableStateFlow(true)

    private val _isGlobalHookEnabled = MutableStateFlow(false)
    val isGlobalHookEnabled: StateFlow<Boolean> = _isGlobalHookEnabled.asStateFlow()

    val filteredHooks: StateFlow<List<CustomHookInfo>> =
        combine(_hookList, _query) { hooks, currentQuery ->
            if (currentQuery.isBlank()) {
                hooks
            } else {
                val lowerCaseQuery = currentQuery.lowercase()
                hooks.filter { hook ->
                    hook.className.lowercase().contains(lowerCaseQuery) ||
                    hook.methodNames?.any { it.lowercase().contains(lowerCaseQuery) } == true ||
                    hook.returnValue?.lowercase()?.contains(lowerCaseQuery) == true ||
                    hook.parameterTypes?.any { it.lowercase().contains(lowerCaseQuery) } == true ||
                    hook.fieldName?.lowercase()?.contains(lowerCaseQuery) == true ||
                    hook.fieldValue?.lowercase()?.contains(lowerCaseQuery) == true
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val selectedHooks: StateFlow<Set<CustomHookInfo>> = _selectedHooks.asStateFlow()
    val query: StateFlow<String> = _query.asStateFlow()
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun initialize(packageName: String?) {
        currentPackageName = packageName
        loadHooks()
        loadGlobalHookEnabledStatus()
    }

    private fun loadHooks() {
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            _hookList.value = repository.getHookConfigs(currentPackageName)
            _isLoading.value = false
        }
    }

    private fun loadGlobalHookEnabledStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _isGlobalHookEnabled.value = repository.getHookEnabledStatus(currentPackageName)
        }
    }

    fun setGlobalHookEnabledStatus(isEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setHookEnabledStatus(currentPackageName, isEnabled)
            _isGlobalHookEnabled.value = isEnabled
        }
    }

    fun setQuery(searchQuery: String) {
        _query.value = searchQuery
    }

    fun addHook(hook: CustomHookInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val hookWithPackage = hook.copy(packageName = currentPackageName)
            val currentHooks = _hookList.value.toMutableList().apply { add(hookWithPackage) }
            repository.saveHookConfigs(currentPackageName, currentHooks)
            _hookList.value = currentHooks.toList()
        }
    }

    fun updateHook(oldHook: CustomHookInfo, newHook: CustomHookInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentHooks = _hookList.value.toMutableList()
            val index = currentHooks.indexOfFirst { it.id == oldHook.id }
            if (index != -1) {
                currentHooks[index] = newHook.copy(id = oldHook.id, isEnabled = oldHook.isEnabled, packageName = currentPackageName)
                repository.saveHookConfigs(currentPackageName, currentHooks)
                _hookList.value = currentHooks.toList()
            }
        }
    }

    fun toggleHookActivation(hook: CustomHookInfo, isEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentHooks = _hookList.value.toMutableList()
            val index = currentHooks.indexOfFirst { it.id == hook.id }
            if (index != -1) {
                currentHooks[index] = hook.copy(isEnabled = isEnabled, packageName = currentPackageName)
                repository.saveHookConfigs(currentPackageName, currentHooks)
                _hookList.value = currentHooks.toList()
            }
        }
    }

    fun deleteHook(hook: CustomHookInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentHooks = _hookList.value.toMutableList().apply { removeIf { it.id == hook.id } }
            repository.saveHookConfigs(currentPackageName, currentHooks)
            _hookList.value = currentHooks.toList()
            _selectedHooks.value = _selectedHooks.value.minus(hook)
        }
    }

    fun clearAllHooks() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveHookConfigs(currentPackageName, emptyList())
            _hookList.value = emptyList()
            _selectedHooks.value = emptySet()
        }
    }

    fun toggleSelection(hook: CustomHookInfo) {
        _selectedHooks.value = if (_selectedHooks.value.contains(hook)) {
            _selectedHooks.value.minus(hook)
        } else {
            _selectedHooks.value.plus(hook)
        }
    }

    fun clearSelection() {
        _selectedHooks.value = emptySet()
    }

    fun deleteSelectedHookConfigs() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentHooks = _hookList.value.toMutableList()
            currentHooks.removeAll(_selectedHooks.value)
            repository.saveHookConfigs(currentPackageName, currentHooks)
            _hookList.value = currentHooks.toList()
            _selectedHooks.value = emptySet()
        }
    }

    suspend fun copySelectedHooksToJson(): String {
        val context = getApplication<Application>()
        if (_selectedHooks.value.isEmpty()) {
            return context.getString(R.string.no_hook_selected_to_copy)
        }

        val hooksToCopy = _selectedHooks.value.map { it.copy(packageName = currentPackageName) }
        val jsonString = Json.encodeToString(hooksToCopy)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(context.getString(R.string.hook_configurations_label), jsonString)
        clipboard.setPrimaryClip(clip)
        return context.getString(R.string.copied_hooks_count, _selectedHooks.value.size)
    }

    fun getExportableHooks(): List<CustomHookInfo> {
        return _hookList.value.map { it.copy(packageName = currentPackageName) }
    }

    suspend fun importHooks(importedHooks: List<CustomHookInfo>): Boolean {
        val currentHooks = _hookList.value.toMutableList()
        var updated = false

        for (importedHook in importedHooks) {
            val hookForCurrentContext = importedHook.copy(packageName = currentPackageName)
            val existingHook = currentHooks.find { it.id == hookForCurrentContext.id }

            if (existingHook == null) {
                currentHooks.add(hookForCurrentContext)
                updated = true
            } else if (existingHook != hookForCurrentContext) {
                val index = currentHooks.indexOfFirst { it.id == existingHook.id }
                if (index != -1) {
                    currentHooks[index] = hookForCurrentContext
                    updated = true
                }
            }
        }

        if (updated) {
            repository.saveHookConfigs(currentPackageName, currentHooks)
            _hookList.value = currentHooks.toList()
        }
        return updated
    }

    suspend fun importGlobalHooksToStorage(globalHooks: List<CustomHookInfo>): Boolean {
        val existingGlobalHooks = repository.getHookConfigs(null).toMutableList()
        var updated = false

        for (importedHook in globalHooks) {
            val hookForGlobal = importedHook.copy(packageName = null)
            if (!existingGlobalHooks.any { it.id == hookForGlobal.id }) {
                existingGlobalHooks.add(hookForGlobal)
                updated = true
            }
        }

        if (updated) {
            repository.saveHookConfigs(null, existingGlobalHooks)
        }
        return updated
    }

    fun getTargetPackageName(): String? = currentPackageName

    suspend fun getAllHooksBlocking(): List<CustomHookInfo> {
        return _hookList.first()
    }
}