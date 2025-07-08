package com.close.hook.ads.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.close.hook.ads.data.model.AppFilterState
import com.close.hook.ads.data.model.AppInfo
import com.close.hook.ads.data.repository.AppRepository
import com.close.hook.ads.preference.PrefManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppsViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val apps: List<AppInfo> = emptyList(),
        val isLoading: Boolean = false
    )

    private val repo = AppRepository(application.packageManager, application.applicationContext)
    private val _filterState = MutableStateFlow(createDefaultFilterState())
    private val _uiState = MutableStateFlow(UiState())

    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refreshApps()
    }

    private fun createDefaultFilterState() = AppFilterState(
        appType = "user",
        filterOrder = PrefManager.order,
        isReverse = PrefManager.isReverse,
        keyword = "",
        showConfigured = PrefManager.configured,
        showUpdated = PrefManager.updated,
        showDisabled = PrefManager.disabled
    )

    private fun combineFlows(): Flow<UiState> {
        return repo.getAllAppsFlow()
            .onStart { _uiState.update { it.copy(isLoading = true) } }
            .combine(_filterState) { rawApps, filter ->
                UiState(
                    apps = repo.filterAndSortApps(rawApps, filter),
                    isLoading = false
                )
            }
            .distinctUntilChanged()
    }
    
    fun refreshApps() {
        viewModelScope.launch {
            combineFlows()
                .collectLatest { _uiState.value = it }
        }
    }

    fun setAppType(type: String) {
        _filterState.update { it.copy(appType = type) }
    }

    fun updateFilterAndSort(
        order: Int,
        keyword: String,
        isReverse: Boolean,
        showConfigured: Boolean,
        showUpdated: Boolean,
        showDisabled: Boolean
    ) {
        _filterState.update {
            it.copy(
                filterOrder = order,
                keyword = keyword,
                isReverse = isReverse,
                showConfigured = showConfigured,
                showUpdated = showUpdated,
                showDisabled = showDisabled
            )
        }
    }
}
